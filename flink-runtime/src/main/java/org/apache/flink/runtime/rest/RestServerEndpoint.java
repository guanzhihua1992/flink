/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.rest;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.IllegalConfigurationException;
import org.apache.flink.runtime.io.network.netty.InboundChannelHandlerFactory;
import org.apache.flink.runtime.io.network.netty.SSLHandlerFactory;
import org.apache.flink.runtime.net.RedirectingSslHandler;
import org.apache.flink.runtime.rest.handler.PipelineErrorHandler;
import org.apache.flink.runtime.rest.handler.RestHandlerSpecification;
import org.apache.flink.runtime.rest.handler.router.Router;
import org.apache.flink.runtime.rest.handler.router.RouterHandler;
import org.apache.flink.runtime.rest.versioning.RestAPIVersion;
import org.apache.flink.util.AutoCloseableAsync;
import org.apache.flink.util.ConfigurationException;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.NetUtils;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.concurrent.ExecutorThreadFactory;
import org.apache.flink.util.concurrent.FutureUtils;

import org.apache.flink.shaded.netty4.io.netty.bootstrap.ServerBootstrap;
import org.apache.flink.shaded.netty4.io.netty.bootstrap.ServerBootstrapConfig;
import org.apache.flink.shaded.netty4.io.netty.channel.Channel;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelFuture;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelHandler;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelInboundHandler;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelInitializer;
import org.apache.flink.shaded.netty4.io.netty.channel.EventLoopGroup;
import org.apache.flink.shaded.netty4.io.netty.channel.nio.NioEventLoopGroup;
import org.apache.flink.shaded.netty4.io.netty.channel.socket.SocketChannel;
import org.apache.flink.shaded.netty4.io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.HttpServerCodec;
import org.apache.flink.shaded.netty4.io.netty.handler.stream.ChunkedWriteHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.Serializable;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** An abstract class for netty-based REST server endpoints. */
public abstract class RestServerEndpoint implements RestService {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final Object lock = new Object();

    private final Configuration configuration;
    private final String restAddress;
    private final String restBindAddress;
    private final String restBindPortRange;
    @Nullable private final SSLHandlerFactory sslHandlerFactory;
    private final int maxContentLength;

    protected final Path uploadDir;
    protected final Map<String, String> responseHeaders;

    private final CompletableFuture<Void> terminationFuture;

    private List<Tuple2<RestHandlerSpecification, ChannelInboundHandler>> handlers;
    private ServerBootstrap bootstrap;
    private Channel serverChannel;
    private String restBaseUrl;
    private int port;

    private State state = State.CREATED;

    @VisibleForTesting List<InboundChannelHandlerFactory> inboundChannelHandlerFactories;

    public RestServerEndpoint(Configuration configuration)
            throws IOException, ConfigurationException {
        Preconditions.checkNotNull(configuration);
        RestServerEndpointConfiguration restConfiguration =
                RestServerEndpointConfiguration.fromConfiguration(configuration);
        Preconditions.checkNotNull(restConfiguration);

        this.configuration = configuration;
        this.restAddress = restConfiguration.getRestAddress();
        this.restBindAddress = restConfiguration.getRestBindAddress();
        this.restBindPortRange = restConfiguration.getRestBindPortRange();
        this.sslHandlerFactory = restConfiguration.getSslHandlerFactory();

        this.uploadDir = restConfiguration.getUploadDir();
        createUploadDir(uploadDir, log, true);

        this.maxContentLength = restConfiguration.getMaxContentLength();
        this.responseHeaders = restConfiguration.getResponseHeaders();

        terminationFuture = new CompletableFuture<>();

        inboundChannelHandlerFactories = new ArrayList<>();
        ServiceLoader<InboundChannelHandlerFactory> loader =
                ServiceLoader.load(InboundChannelHandlerFactory.class);
        final Iterator<InboundChannelHandlerFactory> factories = loader.iterator();
        while (factories.hasNext()) {
            try {
                final InboundChannelHandlerFactory factory = factories.next();
                if (factory != null) {
                    inboundChannelHandlerFactories.add(factory);
                    log.info("Loaded channel inbound factory: {}", factory);
                }
            } catch (Throwable e) {
                log.error("Could not load channel inbound factory.", e);
                throw e;
            }
        }
        inboundChannelHandlerFactories.sort(
                Comparator.comparingInt(InboundChannelHandlerFactory::priority).reversed());
    }

    /**
     * This method is called at the beginning of {@link #start()} to setup all handlers that the
     * REST server endpoint implementation requires.
     *
     * @param localAddressFuture future rest address of the RestServerEndpoint
     * @return Collection of AbstractRestHandler which are added to the server endpoint
     */
    protected abstract List<Tuple2<RestHandlerSpecification, ChannelInboundHandler>>
            initializeHandlers(final CompletableFuture<String> localAddressFuture);

    /**
     * Starts this REST server endpoint.
     *
     * @throws Exception if we cannot start the RestServerEndpoint
     */
    public final void start() throws Exception {
        synchronized (lock) {
            //检查RestServerEndpoint.state是否为CREATED状态。
            Preconditions.checkState(
                    state == State.CREATED, "The RestServerEndpoint cannot be restarted.");

            log.info("Starting rest endpoint.");
            //启动Rest Endpoint，创建Handler使用的路由类Router，用于根据地址寻找对应的Handlers。
            final Router router = new Router();
            final CompletableFuture<String> restAddressFuture = new CompletableFuture<>();
            //调用initializeHandlers()方法初始化子类注册的Handlers，
            // 例如 WebMonitorEndpoint中的Handlers实现。
            handlers = initializeHandlers(restAddressFuture);

            /* sort the handlers such that they are ordered the following:
             * /jobs
             * /jobs/overview
             * /jobs/:jobid
             * /jobs/:jobid/config
             * /:*
             */
            //handlers 按照以上顺序排序
            Collections.sort(handlers, RestHandlerUrlComparator.INSTANCE);
            //handlers 检查 HashSet 保证唯一性
            checkAllEndpointsAndHandlersAreUnique(handlers);
            //调用registerHandler()方法注册已经加载的Handlers。
            handlers.forEach(handler -> registerHandler(router, handler, log));
            //创建ChannelInitializer服务，初始化Netty中的Channel，
            // 在 initChannel()方法中设定SocketChannel中Pipeline使用的拦截器。
            // 在Netty 中使用ServerBootstrap或者bootstrap启动服务端或者客户端时，
            // 会为每 个Channel链接创建一个独立的Pipeline，此时需要将自定义的Handler加 入Pipeline。
            // 这里实际上会将加载的Handlers加入创建的Pipeline。
            // 在 Pipeline中也会按照顺序在尾部增加HttpServerCodec、FileUpload-Handler 以及ChunkedWriteHandler等基础Handlers处理器。
            ChannelInitializer<SocketChannel> initializer =
                    new ChannelInitializer<SocketChannel>() {

                        @Override
                        protected void initChannel(SocketChannel ch) throws ConfigurationException {
                            // 创建路由RouterHandler，完成业务请求拦截
                            RouterHandler handler = new RouterHandler(router, responseHeaders);

                            // SSL should be the first handler in the pipeline
                            // 将SSL放置在第一个Handler上
                            if (isHttpsEnabled()) {
                                ch.pipeline()
                                        .addLast(
                                                "ssl",
                                                new RedirectingSslHandler(
                                                        restAddress,
                                                        restAddressFuture,
                                                        sslHandlerFactory));
                            }

                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new FileUploadHandler(uploadDir))
                                    .addLast(
                                            new FlinkHttpObjectAggregator(
                                                    maxContentLength, responseHeaders));

                            for (InboundChannelHandlerFactory factory :
                                    inboundChannelHandlerFactories) {
                                Optional<ChannelHandler> channelHandler =
                                        factory.createHandler(configuration, responseHeaders);
                                if (channelHandler.isPresent()) {
                                    ch.pipeline().addLast(channelHandler.get());
                                }
                            }

                            ch.pipeline()
                                    .addLast(new ChunkedWriteHandler())
                                    .addLast(handler.getName(), handler)
                                    .addLast(new PipelineErrorHandler(log, responseHeaders));
                        }
                    };
            //创建bossGroup和workerGroup两个NioEventLoopGroup实例，
            // 可 以将其理解为两个线程池，
            // bossGroup设置了一个用于处理连接请求和建立连接的线程，
            // workGroup用于在连接建立之后处理I/O请求。
            NioEventLoopGroup bossGroup =
                    new NioEventLoopGroup(
                            1, new ExecutorThreadFactory("flink-rest-server-netty-boss"));
            NioEventLoopGroup workerGroup =
                    new NioEventLoopGroup(
                            0, new ExecutorThreadFactory("flink-rest-server-netty-worker"));
            //创建ServerBootstrap启动类
            bootstrap = new ServerBootstrap();
            //绑定bossGroup、workerGroup和 initializer等参数。
            bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(initializer);
            //为了防止出现端口占用的情况，从restBindPortRange中抽取端口 范围。
            // 使用bootstrap.bind(chosenPort)按照顺序进行绑定，
            // 如果绑定成功 则调用bind()方法，启动Server-Bootstrap服务，
            // 此时Web端口(默认为 8081)就可以正常访问了
            Iterator<Integer> portsIterator;
            try {//从restBindPortRange选择端口
                portsIterator = NetUtils.getPortRangeFromString(restBindPortRange);
            } catch (IllegalConfigurationException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Invalid port range definition: " + restBindPortRange);
            }
            // 从portsIterator选择没有被占用的端口，作为bootstrap启动的端口
            int chosenPort = 0;
            while (portsIterator.hasNext()) {
                try {
                    chosenPort = portsIterator.next();
                    final ChannelFuture channel;
                    if (restBindAddress == null) {
                        channel = bootstrap.bind(chosenPort);
                    } else {
                        channel = bootstrap.bind(restBindAddress, chosenPort);
                    }
                    serverChannel = channel.syncUninterruptibly().channel();
                    break;
                } catch (final Exception e) {
                    // syncUninterruptibly() throws checked exceptions via Unsafe
                    // continue if the exception is due to the port being in use, fail early
                    // otherwise
                    if (!(e instanceof java.net.BindException)) {
                        throw e;
                    }
                }
            }

            if (serverChannel == null) {
                throw new BindException(
                        "Could not start rest endpoint on any port in port range "
                                + restBindPortRange);
            }
            // ServerBootstrap启动成功，输出restBindAddress和chosenPort
            log.debug("Binding rest endpoint to {}:{}.", restBindAddress, chosenPort);

            final InetSocketAddress bindAddress = (InetSocketAddress) serverChannel.localAddress();
            final String advertisedAddress;
            if (bindAddress.getAddress().isAnyLocalAddress()) {
                advertisedAddress = this.restAddress;
            } else {
                advertisedAddress = bindAddress.getAddress().getHostAddress();
            }

            port = bindAddress.getPort();

            log.info("Rest endpoint listening at {}:{}", advertisedAddress, port);

            restBaseUrl = new URL(determineProtocol(), advertisedAddress, port, "").toString();

            restAddressFuture.complete(restBaseUrl);
            //将RestServerEndpoint中的状态设定为RUNNING，
            // 调用 WebMonitorEndpoint.startInternal()方法，启动RPC高可用服务。
            state = State.RUNNING;
            // 调用内部启动方法，启动RestEndpoint服务
            startInternal();
        }
    }

    /**
     * Hook to start sub class specific services.
     *
     * @throws Exception if an error occurred
     */
    protected abstract void startInternal() throws Exception;

    /**
     * Returns the address on which this endpoint is accepting requests.
     *
     * @return address on which this endpoint is accepting requests or null if none
     */
    @Nullable
    public InetSocketAddress getServerAddress() {
        synchronized (lock) {
            assertRestServerHasBeenStarted();
            Channel server = this.serverChannel;

            if (server != null) {
                try {
                    return ((InetSocketAddress) server.localAddress());
                } catch (Exception e) {
                    log.error("Cannot access local server address", e);
                }
            }

            return null;
        }
    }

    /**
     * Returns the base URL of the REST server endpoint.
     *
     * @return REST base URL of this endpoint
     */
    public String getRestBaseUrl() {
        synchronized (lock) {
            assertRestServerHasBeenStarted();
            return restBaseUrl;
        }
    }

    private void assertRestServerHasBeenStarted() {
        Preconditions.checkState(
                state != State.CREATED, "The RestServerEndpoint has not been started yet.");
    }

    @Override
    public int getRestPort() {
        synchronized (lock) {
            assertRestServerHasBeenStarted();
            return port;
        }
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        synchronized (lock) {
            log.info("Shutting down rest endpoint.");

            if (state == State.RUNNING) {
                final CompletableFuture<Void> shutDownFuture =
                        FutureUtils.composeAfterwards(closeHandlersAsync(), this::shutDownInternal);

                shutDownFuture.whenComplete(
                        (Void ignored, Throwable throwable) -> {
                            log.info("Shut down complete.");
                            if (throwable != null) {
                                terminationFuture.completeExceptionally(throwable);
                            } else {
                                terminationFuture.complete(null);
                            }
                        });
                state = State.SHUTDOWN;
            } else if (state == State.CREATED) {
                terminationFuture.complete(null);
                state = State.SHUTDOWN;
            }

            return terminationFuture;
        }
    }

    private FutureUtils.ConjunctFuture<Void> closeHandlersAsync() {
        return FutureUtils.waitForAll(
                handlers.stream()
                        .map(tuple -> tuple.f1)
                        .filter(handler -> handler instanceof AutoCloseableAsync)
                        .map(handler -> ((AutoCloseableAsync) handler).closeAsync())
                        .collect(Collectors.toList()));
    }

    /**
     * Stops this REST server endpoint.
     *
     * @return Future which is completed once the shut down has been finished.
     */
    protected CompletableFuture<Void> shutDownInternal() {

        synchronized (lock) {
            CompletableFuture<?> channelFuture = new CompletableFuture<>();
            if (serverChannel != null) {
                serverChannel
                        .close()
                        .addListener(
                                finished -> {
                                    if (finished.isSuccess()) {
                                        channelFuture.complete(null);
                                    } else {
                                        channelFuture.completeExceptionally(finished.cause());
                                    }
                                });
                serverChannel = null;
            }

            final CompletableFuture<Void> channelTerminationFuture = new CompletableFuture<>();

            channelFuture.thenRun(
                    () -> {
                        CompletableFuture<?> groupFuture = new CompletableFuture<>();
                        CompletableFuture<?> childGroupFuture = new CompletableFuture<>();
                        final Time gracePeriod = Time.seconds(10L);

                        if (bootstrap != null) {
                            final ServerBootstrapConfig config = bootstrap.config();
                            final EventLoopGroup group = config.group();
                            if (group != null) {
                                group.shutdownGracefully(
                                                0L,
                                                gracePeriod.toMilliseconds(),
                                                TimeUnit.MILLISECONDS)
                                        .addListener(
                                                finished -> {
                                                    if (finished.isSuccess()) {
                                                        groupFuture.complete(null);
                                                    } else {
                                                        groupFuture.completeExceptionally(
                                                                finished.cause());
                                                    }
                                                });
                            } else {
                                groupFuture.complete(null);
                            }

                            final EventLoopGroup childGroup = config.childGroup();
                            if (childGroup != null) {
                                childGroup
                                        .shutdownGracefully(
                                                0L,
                                                gracePeriod.toMilliseconds(),
                                                TimeUnit.MILLISECONDS)
                                        .addListener(
                                                finished -> {
                                                    if (finished.isSuccess()) {
                                                        childGroupFuture.complete(null);
                                                    } else {
                                                        childGroupFuture.completeExceptionally(
                                                                finished.cause());
                                                    }
                                                });
                            } else {
                                childGroupFuture.complete(null);
                            }

                            bootstrap = null;
                        } else {
                            // complete the group futures since there is nothing to stop
                            groupFuture.complete(null);
                            childGroupFuture.complete(null);
                        }

                        CompletableFuture<Void> combinedFuture =
                                FutureUtils.completeAll(
                                        Arrays.asList(groupFuture, childGroupFuture));

                        combinedFuture.whenComplete(
                                (Void ignored, Throwable throwable) -> {
                                    if (throwable != null) {
                                        channelTerminationFuture.completeExceptionally(throwable);
                                    } else {
                                        channelTerminationFuture.complete(null);
                                    }
                                });
                    });

            return channelTerminationFuture;
        }
    }

    private boolean isHttpsEnabled() {
        return sslHandlerFactory != null;
    }

    private String determineProtocol() {
        return isHttpsEnabled() ? "https" : "http";
    }

    private static void registerHandler(
            Router router,
            Tuple2<RestHandlerSpecification, ChannelInboundHandler> specificationHandler,
            Logger log) {
        final String handlerURL = specificationHandler.f0.getTargetRestEndpointURL();
        // setup versioned urls
        for (final RestAPIVersion supportedVersion :
                specificationHandler.f0.getSupportedAPIVersions()) {
            final String versionedHandlerURL =
                    '/' + supportedVersion.getURLVersionPrefix() + handlerURL;
            log.debug(
                    "Register handler {} under {}@{}.",
                    specificationHandler.f1,
                    specificationHandler.f0.getHttpMethod(),
                    versionedHandlerURL);
            registerHandler(
                    router,
                    versionedHandlerURL,
                    specificationHandler.f0.getHttpMethod(),
                    specificationHandler.f1);
            if (supportedVersion.isDefaultVersion()) {
                // setup unversioned url for convenience and backwards compatibility
                log.debug(
                        "Register handler {} under {}@{}.",
                        specificationHandler.f1,
                        specificationHandler.f0.getHttpMethod(),
                        handlerURL);
                registerHandler(
                        router,
                        handlerURL,
                        specificationHandler.f0.getHttpMethod(),
                        specificationHandler.f1);
            }
        }
    }

    private static void registerHandler(
            Router router,
            String handlerURL,
            HttpMethodWrapper httpMethod,
            ChannelInboundHandler handler) {
        switch (httpMethod) {
            case GET:
                router.addGet(handlerURL, handler);
                break;
            case POST:
                router.addPost(handlerURL, handler);
                break;
            case DELETE:
                router.addDelete(handlerURL, handler);
                break;
            case PATCH:
                router.addPatch(handlerURL, handler);
                break;
            case PUT:
                router.addPut(handlerURL, handler);
                break;
            default:
                throw new RuntimeException("Unsupported http method: " + httpMethod + '.');
        }
    }

    /** Creates the upload dir if needed. */
    static void createUploadDir(
            final Path uploadDir, final Logger log, final boolean initialCreation)
            throws IOException {
        if (!Files.exists(uploadDir)) {
            if (initialCreation) {
                log.info("Upload directory {} does not exist. ", uploadDir);
            } else {
                log.warn(
                        "Upload directory {} has been deleted externally. "
                                + "Previously uploaded files are no longer available.",
                        uploadDir);
            }
            checkAndCreateUploadDir(uploadDir, log);
        }
    }

    /**
     * Checks whether the given directory exists and is writable. If it doesn't exist, this method
     * will attempt to create it.
     *
     * @param uploadDir directory to check
     * @param log logger used for logging output
     * @throws IOException if the directory does not exist and cannot be created, or if the
     *     directory isn't writable
     */
    private static synchronized void checkAndCreateUploadDir(final Path uploadDir, final Logger log)
            throws IOException {
        if (Files.exists(uploadDir) && Files.isWritable(uploadDir)) {
            log.info("Using directory {} for file uploads.", uploadDir);
        } else if (Files.isWritable(Files.createDirectories(uploadDir))) {
            log.info("Created directory {} for file uploads.", uploadDir);
        } else {
            log.warn("Upload directory {} cannot be created or is not writable.", uploadDir);
            throw new IOException(
                    String.format(
                            "Upload directory %s cannot be created or is not writable.",
                            uploadDir));
        }
    }

    private static void checkAllEndpointsAndHandlersAreUnique(
            final List<Tuple2<RestHandlerSpecification, ChannelInboundHandler>> handlers) {
        // check for all handlers that
        // 1) the instance is only registered once
        // 2) only 1 handler is registered for each endpoint (defined by (version, method, url))
        // technically the first check is redundant since a duplicate instance also returns the same
        // headers which
        // should fail the second check, but we get a better error message
        final Set<String> uniqueEndpoints = new HashSet<>();
        final Set<ChannelInboundHandler> distinctHandlers =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (Tuple2<RestHandlerSpecification, ChannelInboundHandler> handler : handlers) {
            boolean isNewHandler = distinctHandlers.add(handler.f1);
            if (!isNewHandler) {
                throw new FlinkRuntimeException(
                        "Duplicate REST handler instance found."
                                + " Please ensure each instance is registered only once.");
            }

            final RestHandlerSpecification headers = handler.f0;
            for (RestAPIVersion supportedRestAPIVersion : headers.getSupportedAPIVersions()) {
                final String parameterizedEndpoint =
                        supportedRestAPIVersion.toString()
                                + headers.getHttpMethod()
                                + headers.getTargetRestEndpointURL();
                // normalize path parameters; distinct path parameters still clash at runtime
                final String normalizedEndpoint =
                        parameterizedEndpoint.replaceAll(":[\\w-]+", ":param");
                boolean isNewEndpoint = uniqueEndpoints.add(normalizedEndpoint);
                if (!isNewEndpoint) {
                    throw new FlinkRuntimeException(
                            String.format(
                                    "REST handler registration overlaps with another registration for: version=%s, method=%s, url=%s.",
                                    supportedRestAPIVersion,
                                    headers.getHttpMethod(),
                                    headers.getTargetRestEndpointURL()));
                }
            }
        }
    }

    /**
     * Comparator for Rest URLs.
     *
     * <p>The comparator orders the Rest URLs such that URLs with path parameters are ordered behind
     * those without parameters. E.g.: /jobs /jobs/overview /jobs/:jobid /jobs/:jobid/config /:*
     *
     * <p>IMPORTANT: This comparator is highly specific to how Netty path parameters are encoded.
     * Namely with a preceding ':' character.
     */
    public static final class RestHandlerUrlComparator
            implements Comparator<Tuple2<RestHandlerSpecification, ChannelInboundHandler>>,
                    Serializable {

        private static final long serialVersionUID = 2388466767835547926L;

        private static final Comparator<String> CASE_INSENSITIVE_ORDER =
                new CaseInsensitiveOrderComparator();

        static final RestHandlerUrlComparator INSTANCE = new RestHandlerUrlComparator();

        @Override
        public int compare(
                Tuple2<RestHandlerSpecification, ChannelInboundHandler> o1,
                Tuple2<RestHandlerSpecification, ChannelInboundHandler> o2) {
            final int urlComparisonResult =
                    CASE_INSENSITIVE_ORDER.compare(
                            o1.f0.getTargetRestEndpointURL(), o2.f0.getTargetRestEndpointURL());
            if (urlComparisonResult != 0) {
                return urlComparisonResult;
            } else {
                Collection<? extends RestAPIVersion> o1APIVersions =
                        o1.f0.getSupportedAPIVersions();
                RestAPIVersion o1Version = Collections.min(o1APIVersions);
                Collection<? extends RestAPIVersion> o2APIVersions =
                        o2.f0.getSupportedAPIVersions();
                RestAPIVersion o2Version = Collections.min(o2APIVersions);
                return o1Version.compareTo(o2Version);
            }
        }

        /**
         * Comparator for Rest URLs.
         *
         * <p>The comparator orders the Rest URLs such that URLs with path parameters are ordered
         * behind those without parameters. E.g.: /jobs /jobs/overview /jobs/:jobid
         * /jobs/:jobid/config /:*
         *
         * <p>IMPORTANT: This comparator is highly specific to how Netty path parameters are
         * encoded. Namely with a preceding ':' character.
         */
        public static final class CaseInsensitiveOrderComparator
                implements Comparator<String>, Serializable {
            private static final long serialVersionUID = 8550835445193437027L;

            @Override
            public int compare(String s1, String s2) {
                int n1 = s1.length();
                int n2 = s2.length();
                int min = Math.min(n1, n2);
                for (int i = 0; i < min; i++) {
                    char c1 = s1.charAt(i);
                    char c2 = s2.charAt(i);
                    if (c1 != c2) {
                        c1 = Character.toUpperCase(c1);
                        c2 = Character.toUpperCase(c2);
                        if (c1 != c2) {
                            c1 = Character.toLowerCase(c1);
                            c2 = Character.toLowerCase(c2);
                            if (c1 != c2) {
                                if (c1 == ':') {
                                    // c2 is less than c1 because it is also different
                                    return 1;
                                } else if (c2 == ':') {
                                    // c1 is less than c2
                                    return -1;
                                } else {
                                    return c1 - c2;
                                }
                            }
                        }
                    }
                }
                return n1 - n2;
            }
        }
    }

    private enum State {
        CREATED,
        RUNNING,
        SHUTDOWN
    }
}

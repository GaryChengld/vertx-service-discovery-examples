package io.examples.circuitbreaker.vertx.service;

import io.reactivex.Single;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.core.http.HttpServerRequest;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.servicediscovery.ServiceDiscovery;
import io.vertx.reactivex.servicediscovery.types.HttpEndpoint;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.rest.ServiceDiscoveryRestEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.examples.common.HttpResponseCodes.SC_SERVICE_UNAVAILABLE;

/**
 * Verticle for service A
 *
 * @author Gary Cheng
 */
public class ServiceAVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(ServiceAVerticle.class);
    private static final String KEY_SERVICE = "service";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String SERVICE_A_PATH = "/serviceA/";
    private ServiceDiscovery discovery;
    private Record publishedRecord;

    // Convenience method so you can run it in IDE
    public static void main(String[] args) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.put(KEY_SERVICE, "ServiceA");
        jsonObject.put(KEY_HOST, "localhost");
        jsonObject.put(KEY_PORT, 9080);
        Vertx.rxClusteredVertx(new VertxOptions().setClustered(true))
                .flatMap(vertx -> vertx.rxDeployVerticle(ServiceAVerticle.class.getName(), new DeploymentOptions().setConfig(jsonObject)))
                .subscribe(id -> logger.debug("Service A Verticle deployed successfully with deployment ID {}", id),
                        t -> logger.error(t.getLocalizedMessage()));
    }

    @Override
    public void start(Future<Void> startFuture) {
        logger.debug("Starting Service A Verticle");
        int port = this.config().getInteger(KEY_PORT, 9080);
        this.discovery = ServiceDiscovery.create(vertx);
        Router router = this.router(vertx);
        HttpServer server = vertx.createHttpServer();
        server.requestStream()
                .toFlowable()
                .map(HttpServerRequest::pause)
                .onBackpressureDrop(req -> req.response().setStatusCode(SC_SERVICE_UNAVAILABLE).end())
                .subscribe(req -> {
                    logger.debug("Received HTTP request");
                    req.resume();
                    router.accept(req);
                });
        server.rxListen(port)
                .doAfterSuccess(s -> logger.debug("http server started on port {}", s.actualPort()))
                .flatMap(s -> this.registerService(this.config()))
                .doAfterSuccess(r -> logger.debug("ServiceA published"))
                .subscribe(s -> startFuture.complete(), startFuture::fail);
    }

    @Override
    public void stop(Future<Void> stopFuture) {
        if (null != this.publishedRecord) {
            discovery.rxUnpublish(this.publishedRecord.getRegistration())
                    .doOnComplete(() -> logger.debug("ServiceA unPublished"))
                    .subscribe(stopFuture::complete);
        }
    }

    private Router router(Vertx vertx) {
        Router router = Router.router(vertx);
        ServiceDiscoveryRestEndpoint.create(router.getDelegate(), discovery.getDelegate());
        router.route(SERVICE_A_PATH).handler(this::handleService);
        return router;
    }

    private void handleService(RoutingContext context) {
        JsonObject result = new JsonObject();
        result.put("ServiceA result", "Welcome to Service A");
        context.response().putHeader("content-type", "application/json").end(result.encodePrettily());
    }

    private Single<Record> registerService(JsonObject config) {
        return this.discovery.rxPublish(this.createRecord(config))
                .doOnSuccess(record -> this.publishedRecord = record);
    }

    private Record createRecord(JsonObject config) {
        return HttpEndpoint.createRecord(config.getString(KEY_SERVICE), config.getString(KEY_HOST),
                config.getInteger(KEY_PORT), "SERVICE_A_PATH");
    }
}

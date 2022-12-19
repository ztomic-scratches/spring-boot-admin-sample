package org.demo.sba;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Application;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.netflix.eureka.EurekaServiceInstance;
import org.springframework.cloud.netflix.eureka.server.event.EurekaInstanceCanceledEvent;
import org.springframework.cloud.netflix.eureka.server.event.EurekaInstanceRegisteredEvent;
import org.springframework.cloud.netflix.eureka.server.event.EurekaInstanceRenewedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.util.PatternMatchUtils;

/**
 * Modified implementation of old {@link de.codecentric.boot.admin.server.cloud.discovery.InstanceDiscoveryListener} listener, 
 * so we don't need discovery clien because Spring Boot Admin is embedded to Discovery Server
 */
public class SpringBootAdminInstanceRegistryListener {
    private static final Logger log = LoggerFactory.getLogger(SpringBootAdminInstanceRegistryListener.class);
    private static final String SOURCE = "discovery";
    private final InstanceRegistry registry;
    private final InstanceRepository repository;
    private DefaultServiceInstanceConverter converter = new DefaultServiceInstanceConverter();

    /**
     * Set of serviceIds to be ignored and not to be registered as application. Supports simple
     * patterns (e.g. "foo*", "*foo", "foo*bar").
     */
    private Set<String> ignoredServices = new HashSet<>();

    /**
     * Set of serviceIds that has to match to be registered as application. Supports simple
     * patterns (e.g. "foo*", "*foo", "foo*bar"). Default value is everything
     */
    private Set<String> services = new HashSet<>(Collections.singletonList("*"));

    public SpringBootAdminInstanceRegistryListener(InstanceRegistry registry,
            InstanceRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @EventListener
    public void onEurekaInstanceCanceledEvent(EurekaInstanceCanceledEvent event) {
        log.trace("Eureka instance canceled: {}", event);
        discover();
    }

    @EventListener
    public void onEurekaInstanceRenewedEvent(EurekaInstanceRenewedEvent event) {
        log.trace("Eureka instance renewed: {}", event);
        register(event.getInstanceInfo());
    }

    @EventListener
    public void onEurekaInstanceRegisteredEvent(EurekaInstanceRegisteredEvent event) {
        log.trace("Eureka instance registered: {}", event);
        register(event.getInstanceInfo());
    }

    private PeerAwareInstanceRegistry getRegistry() {
        return this.getServerContext().getRegistry();
    }

    private EurekaServerContext getServerContext() {
        return EurekaServerContextHolder.getInstance().getServerContext();
    }

    public void discover() {
        if (EurekaServerContextHolder.getInstance() == null) {
            return;
        }
        Flux.fromIterable(getRegistry().getSortedApplications())
            .filter(this::shouldRegisterService)
            .flatMapIterable(Application::getInstances)
            .map(EurekaServiceInstance::new)
            .flatMap(this::registerInstance)
            .collect(Collectors.toSet())
            .flatMap(this::removeStaleInstances)
            .subscribe(v -> { }, ex -> log.error("Unexpected error.", ex));
    }

    private void register(InstanceInfo instanceInfo) {
        if (instanceInfo == null) {
            return;
        }
        if (shouldRegisterService(instanceInfo.getAppName())) {
            registerInstance(new EurekaServiceInstance(instanceInfo))
                    .subscribe(v -> { }, ex -> log.error("Unexpected error.", ex));
        }
    }

    protected Mono<Void> removeStaleInstances(Set<InstanceId> registeredInstanceIds) {
        return repository.findAll()
                         .filter(Instance::isRegistered)
                         .filter(instance -> SOURCE.equals(instance.getRegistration().getSource()))
                         .map(Instance::getId)
                         .filter(id -> !registeredInstanceIds.contains(id))
                         .doOnNext(id -> log.info("Instance ({}) missing in DiscoveryClient services ", id))
                         .flatMap(registry::deregister)
                         .then();
    }

    protected boolean shouldRegisterService(final Application application) {
        return shouldRegisterService(application.getName());
    }

    protected boolean shouldRegisterService(final String serviceId) {
        boolean shouldRegister = matchesPattern(serviceId, services) && !matchesPattern(serviceId, ignoredServices);
        if (!shouldRegister) {
            log.trace("Ignoring discovered service {}", serviceId);
        }
        return shouldRegister;
    }

    protected boolean matchesPattern(String serviceId, Set<String> patterns) {
        return patterns.stream().anyMatch(pattern -> PatternMatchUtils.simpleMatch(pattern, serviceId));
    }

    protected Mono<InstanceId> registerInstance(ServiceInstance instance) {
        try {
            Registration registration = converter.convert(instance).toBuilder().source(SOURCE).build();
            log.trace("Registering discovered instance {}", registration);
            return registry.register(registration);
        } catch (Exception ex) {
            log.error("Couldn't register instance for service {}", instance, ex);
        }
        return Mono.empty();
    }

    public void setIgnoredServices(Set<String> ignoredServices) {
        this.ignoredServices = ignoredServices;
    }

    public Set<String> getIgnoredServices() {
        return ignoredServices;
    }

    public Set<String> getServices() {
        return services;
    }

    public void setServices(Set<String> services) {
        this.services = services;
    }
    
}

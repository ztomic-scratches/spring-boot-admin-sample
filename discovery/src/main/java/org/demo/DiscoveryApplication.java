package org.demo;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import de.codecentric.boot.admin.server.config.EnableAdminServer;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.services.HashingInstanceUrlIdGenerator;
import de.codecentric.boot.admin.server.services.InstanceIdGenerator;
import de.codecentric.boot.admin.server.services.InstanceRegistry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import org.demo.sba.SpringBootAdminInstanceRegistryListener;

@SpringBootApplication
@EnableEurekaServer
@ConfigurationPropertiesScan
public class DiscoveryApplication {

	@Configuration
	@EnableAdminServer
	static class SpringBootAdminEnabler implements WebMvcConfigurer {
		
		private final AdminServerProperties adminServerProperties;

		SpringBootAdminEnabler(AdminServerProperties adminServerProperties) {
			this.adminServerProperties = adminServerProperties;
		}

		@Bean
		public InstanceIdGenerator instanceIdGenerator() {
			return registration -> {
				String microserviceName = registration.getMetadata().get("name");
				String microserviceInstanceId = registration.getMetadata().get("instance-id");
				if (StringUtils.hasText(microserviceInstanceId)) {
					if (StringUtils.hasText(microserviceName)) {
						return InstanceId.of(microserviceName + ":" + microserviceInstanceId);
					}
					return InstanceId.of(microserviceInstanceId);
				}
				return new HashingInstanceUrlIdGenerator().generateId(registration);
			};
		}

		@Bean
		@ConditionalOnMissingBean
		@ConfigurationProperties("spring.boot.admin.registry")
		public SpringBootAdminInstanceRegistryListener instanceDiscoveryListener(InstanceRegistry registry,
				InstanceRepository repository) {
			return new SpringBootAdminInstanceRegistryListener(registry, repository);
		}

		@Override
		public void addViewControllers(ViewControllerRegistry registry) {
			registry.addRedirectViewController("/", adminServerProperties.getContextPath());
		}

	}

	public static void main(String[] args) {
		SpringApplication.run(DiscoveryApplication.class, args);
	}

}

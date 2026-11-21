package com.agri.pit.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI pointInTimeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agriculture drought claim point-in-time feature service")
                        .version("1.0.0")
                        .description("""
                                Feature catalog, point-in-time warehouse, backfill orchestrator, leakage detector,
                                scoring snapshots, revision impact analysis, tenure versioning, case geometry
                                freezing, boundary overlap analysis, pixel allocation/arbitration and the dispute desk.

                                **Two clocks.** Every warehouse record stores `observedAt` (when the phenomenon happened)
                                and `ingestedAt` (when the platform could actually see it). Features are always selected
                                with `ingestedAt <= availabilityCeiling`, never by observation time alone.

                                **Guarantees.**
                                1. A scene acquired before the hazard but ingested after the scoring cutoff is excluded
                                   from the original run and recorded as a leakage exclusion.
                                2. When two parcel boundary versions cover the same ground, each raster cell belongs to
                                   exactly one version; the case's locked version wins and a boundary-variant run sees the
                                   shared pixels excluded instead of double counting them.
                                3. A weather revision that arrives after settlement only creates a pending review
                                   candidate. The frozen snapshot and the settled payout are never rewritten.
                                4. A case is scored only on the parcel geometry APPROVED and visible at intake; the
                                   geometry freeze is immutable, so later transfers/re-surveys cannot silently move a
                                   historical case's area. Cross-parcel overlaps beyond the policy tolerance queue their
                                   pixels for arbitration, and dispute reviews always create a fresh sampling run while
                                   retaining every prior ruling and the original area result.
                                """)
                        .contact(new Contact().name("Claims platform"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url("/").description("Local instance")));
    }
}

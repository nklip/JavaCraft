package dev.nklip.javacraft.shardshop.workload.seeder.http;

import dev.nklip.javacraft.shardshop.workload.seeder.load.SeederRuns;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Starts one finite seed run of the interactive seeder; the shared control resource serves its status. */
@Path("/control/runs")
@Produces(MediaType.APPLICATION_JSON)
public class SeedRunResource {
    private final SeederRuns runs;

    @Inject
    public SeedRunResource(SeederRuns runs) {
        this.runs = runs;
    }

    @POST
    public Response start() {
        return Response.accepted(runs.start()).build();
    }
}

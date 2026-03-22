package my.javacraft.elastic.data.cucumber.step;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.api.model.VoteRequest;
import my.javacraft.elastic.api.model.VoteResponse;
import my.javacraft.elastic.app.service.DateService;
import my.javacraft.elastic.app.service.SchedulerService;
import my.javacraft.elastic.app.service.VoteService;
import my.javacraft.elastic.data.cucumber.conf.CucumberSpringConfiguration;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Slf4j
@Scope(SCOPE_CUCUMBER_GLUE)
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class SchedulerJobsStepDefinitions {

    @Autowired
    VoteService voteService;
    @Autowired
    DateService dateService;
    @Autowired
    SchedulerService schedulerService;

    @Given("there are {int} outdated records")
    public void createOutdatedRecords(Integer records) throws IOException {
        if (records <= 0) {
            throw new IllegalArgumentException("The amount of records should be positive!");
        }
        log.info("creating outdated records...");

        List<VoteResponse> responses = new ArrayList<>();
        for (int i = 0; i < records; i++) {
            VoteRequest voteRequest = new VoteRequest();
            voteRequest.setPostId("post-id-" + i);
            voteRequest.setAction("Upvote");
            voteRequest.setUserId("nl8111");

            VoteResponse voteResponse = voteService.processVoteRequest(voteRequest,
                    dateService.getNDaysBeforeDate(400 + i)
            );
            responses.add(voteResponse);
        }

        log.info("created outdated records = {}", responses.size());
    }

    @Then("execute cleanup job with expected result of {long}")
    public void executeCleanUpJob(Long expectedResult) throws InterruptedException {
        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(
                        expectedResult,
                        () -> schedulerService.removeOldUserVotes()
                )
        );
    }
}

package my.javacraft.elastic.cucumber.step;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch.core.CountRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.cucumber.conf.CucumberSpringConfiguration;
import my.javacraft.elastic.model.SeekType;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserClickResponse;
import my.javacraft.elastic.service.DateService;
import my.javacraft.elastic.service.SchedulerService;
import my.javacraft.elastic.service.activity.UserActivityIngestionService;
import my.javacraft.elastic.service.activity.UserActivityService;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Slf4j
@Scope(SCOPE_CUCUMBER_GLUE)
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class SchedulerJobsStepDefinitions {

    @Autowired
    UserActivityIngestionService userActivityIngestionService;
    @Autowired
    DateService dateService;
    @Autowired
    SchedulerService schedulerService;
    @Autowired
    ElasticsearchClient esClient;

    @Given("there are no outdated records")
    public void ensureThereAreNoOutdatedRecords() throws InterruptedException {
        log.info("confirming there are no outdated records...");

        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(0L, this::countOutdatedRecords)
        );

        log.info("confirmed there are no outdated records");
    }

    @Given("there are {int} outdated records")
    public void createOutdatedRecords(Integer records) throws IOException {
        if (records <= 0) {
            throw new IllegalArgumentException("The amount of records should be positive!");
        }
        log.info("creating outdated records...");

        List<UserClickResponse> responses = new ArrayList<>();
        for (int i = 0; i < records; i++) {
            UserClick userClick = new UserClick();
            userClick.setPostId("post-id-" + i);
            userClick.setSearchType(SeekType.PEOPLE.toString());
            userClick.setAction("Upvote");
            userClick.setUserId("nl8111");
            userClick.setSearchPattern("FIXED INCOME");

            UserClickResponse userClickResponse = userActivityIngestionService.ingestUserClick(
                    userClick, dateService.getNDaysBeforeDate(200 + i));
            responses.add(userClickResponse);
        }

        log.info("created outdated records = {}", responses.size());
    }

    @Then("execute cleanup job with expected result of {long}")
    public void executeCleanUpJob(Long expectedResult) throws InterruptedException {
        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(
                        expectedResult,
                        () -> schedulerService.removeOldActivityRecords()
                )
        );
    }

    private long countOutdatedRecords() {
        try {
            RangeQuery rangeQuery = RangeQuery
                    .of(r -> r.date(d -> d
                            .field(UserActivityService.TIMESTAMP)
                            .lte(dateService.getNDaysBeforeDate(UserActivityService.SIX_MONTHS))
                    )
            );
            CountRequest countRequest = new CountRequest.Builder()
                    .index(UserActivityService.INDEX_USER_ACTIVITY)
                    .query(rangeQuery._toQuery())
                    .build();
            return esClient.count(countRequest).count();
        } catch (IOException ioe) {
            log.error(ioe.getMessage(), ioe);
            return -1;
        }
    }
}

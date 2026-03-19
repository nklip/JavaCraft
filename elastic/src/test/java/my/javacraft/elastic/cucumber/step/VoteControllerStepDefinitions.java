package my.javacraft.elastic.cucumber.step;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.config.Constants;
import my.javacraft.elastic.model.Post;
import my.javacraft.elastic.model.UserVote;
import my.javacraft.elastic.model.VoteRequest;
import my.javacraft.elastic.model.VoteResponse;
import org.apache.http.HttpHeaders;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Slf4j
@Scope(SCOPE_CUCUMBER_GLUE)
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class VoteControllerStepDefinitions {

    /** Anchor constant from PostService — Dec 8, 2005 (Reddit launch epoch). */
    private static final long HOT_EPOCH_ANCHOR = 1_134_028_003L;
    private static final double HOT_TIME_SCALE = 45_000.0;

    @LocalServerPort
    int port;

    @Autowired
    ElasticsearchClient esClient;

    /** Holds the response from the most recent VoteController call. */
    private VoteResponse lastVoteResponse;

    /**
     * Creates a post document directly in ES, bypassing the production API.
     * Needed so that {@code PostService#updateScores} finds an existing document when votes arrive.
     */
    @Given("post {string} exists in {string} index with author {string}")
    public void createPost(String postId, String index, String author) throws IOException {
        String createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        long epochSec = Instant.parse(createdAt).getEpochSecond();
        double hotScore = (epochSec - HOT_EPOCH_ANCHOR) / HOT_TIME_SCALE;
        Post post = new Post(postId, author, createdAt, 0L, hotScore);
        esClient.index(i -> i.index(index).id(postId).document(post));
        log.info("created post '{}' in index '{}' for author '{}'", postId, index, author);
    }

    /**
     * Sends a vote via {@code POST /api/services/user-votes} and stores the response.
     * Annotated with both {@code @Given} and {@code @When} so it can be used as a
     * setup step (Given/And) or the main action under test (When).
     */
    @When("user {string} sends {word} on post {string}")
    public void sendVote(String userId, String action, String postId) {
        VoteRequest request = new VoteRequest();
        request.setUserId(userId);
        request.setPostId(postId);
        request.setAction(action);

        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<VoteRequest> entity = new HttpEntity<>(request, headers);

        String url = "http://localhost:%d/api/services/user-votes".formatted(port);
        ResponseEntity<VoteResponse> response = new RestTemplate().exchange(
                url, HttpMethod.POST, entity, VoteResponse.class
        );
        lastVoteResponse = response.getBody();
        log.info("sent {} by '{}' on '{}' → result: {}",
                action, userId, postId, lastVoteResponse != null ? lastVoteResponse.getResult() : null);
    }

    /**
     * Asserts that the ES result from the last VoteController call matches {@code expectedResult}.
     * Expected values: Created, Updated, NoOp, Deleted, NotFound.
     */
    @Then("the vote response result is {string}")
    public void checkVoteResponseResult(String expectedResult) {
        Assertions.assertNotNull(lastVoteResponse, "No vote response recorded in this scenario");
        Assertions.assertNotNull(lastVoteResponse.getResult(), "VoteResponse.result is null");
        Assertions.assertEquals(expectedResult, lastVoteResponse.getResult().name(),
                "Unexpected ES result for last vote");
    }

    /**
     * Asserts that a vote document exists in the {@value Constants#INDEX_USER_VOTES} index
     * for the given (userId, postId) pair with the expected action.
     * Document ID: {@code userId_postId}.
     */
    @And("a vote exists for user {string} on post {string} with action {string}")
    public void checkVoteExists(String userId, String postId, String expectedAction) throws IOException {
        String docId = userId + "_" + postId;
        var response = esClient.get(g -> g.index(Constants.INDEX_USER_VOTES).id(docId), UserVote.class);
        Assertions.assertTrue(response.found(), "Expected vote document '%s' to exist".formatted(docId));
        Assertions.assertNotNull(response.source(), "Vote document '%s' has no source".formatted(docId));
        Assertions.assertEquals(expectedAction, response.source().getAction(),
                "Vote action mismatch for document '%s'".formatted(docId));
    }

    /**
     * Asserts that NO vote document exists in the {@value Constants#INDEX_USER_VOTES} index
     * for the given (userId, postId) pair — used after NOVOTE (Deleted) and NotFound transitions.
     */
    @And("no vote exists for user {string} on post {string}")
    public void checkVoteNotExists(String userId, String postId) throws IOException {
        String docId = userId + "_" + postId;
        var response = esClient.get(g -> g.index(Constants.INDEX_USER_VOTES).id(docId), UserVote.class);
        Assertions.assertFalse(response.found(),
                "Expected vote document '%s' to be absent, but it was found".formatted(docId));
    }

    /**
     * Asserts the current karma of a post in the {@value Constants#INDEX_POSTS} index.
     * Uses the real-time Get API which reads from the primary shard directly,
     * bypassing the refresh cycle — no explicit refresh needed.
     */
    @And("post {string} karma is {long}")
    public void checkPostKarma(String postId, long expectedKarma) throws IOException {
        var response = esClient.get(g -> g.index(Constants.INDEX_POSTS).id(postId), Post.class);
        Assertions.assertTrue(response.found(), "Post document '%s' not found".formatted(postId));
        Assertions.assertNotNull(response.source(), "Post document '%s' has no source".formatted(postId));
        Assertions.assertEquals(expectedKarma, response.source().karma(),
                "Karma mismatch for post '%s'".formatted(postId));
    }
}

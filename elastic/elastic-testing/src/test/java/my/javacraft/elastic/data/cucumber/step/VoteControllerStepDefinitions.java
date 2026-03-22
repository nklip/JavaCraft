package my.javacraft.elastic.data.cucumber.step;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.app.config.ElasticsearchConstants;
import my.javacraft.elastic.api.model.*;
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

    @LocalServerPort
    int port;

    @Autowired
    ElasticsearchClient esClient;

    /** Holds the response from the most recent VoteController call. */
    private VoteResponse lastVoteResponse;

    /**
     * Maps feature-file aliases (e.g. "post-01") to server-generated postIds returned by
     * {@code PostController}. Required because {@code PostService#submitPost} generates IDs
     * server-side via {@code IdGenerator}; the alias is used only within the scenario to
     * correlate the creation step with subsequent vote and assertion steps.
     */
    private final Map<String, String> postIdAliases = new HashMap<>();

    /**
     * Resolves a feature-file alias to the actual server-generated postId.
     * Falls back to the alias itself so the method is safe to call even when no
     * alias was registered (e.g. direct postId values in other tests).
     */
    private String resolvePostId(String alias) {
        return postIdAliases.getOrDefault(alias, alias);
    }

    /**
     * Submits a new post via {@code POST /api/services/posts} and registers the
     * server-generated {@code postId} under the given {@code postAlias} so that
     * subsequent steps in the same scenario can reference it by alias.
     * The {@code index} parameter is validated to be "posts" — {@code PostController}
     * does not support other indices.
     */
    @Given("post {string} exists in {string} index with author {string}")
    public void createPost(String postAlias, String index, String author) {
        Assertions.assertEquals("posts", index,
                "PostController only supports the 'posts' index, got: " + index);

        PostRequest request = new PostRequest();
        request.setAuthorUserId(author);

        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<PostRequest> entity = new HttpEntity<>(request, headers);

        String url = "http://localhost:%d/api/services/posts".formatted(port);
        ResponseEntity<Post> response = new RestTemplate().exchange(
                url, HttpMethod.POST, entity, Post.class
        );
        Post post = response.getBody();
        Assertions.assertNotNull(post, "PostController returned null body for alias '%s'".formatted(postAlias));
        Assertions.assertNotNull(post.postId(), "PostController returned a post with null postId");

        postIdAliases.put(postAlias, post.postId());
        log.info("created post alias='{}' → postId='{}' in index '{}' for author '{}'",
                postAlias, post.postId(), index, author);
    }

    /**
     * Sends a vote via {@code POST /api/services/user-votes} and stores the response.
     * Annotated with both {@code @Given} and {@code @When} so it can be used as a
     * setup step (Given/And) or the main action under test (When).
     */
    @When("user {string} sends {word} on post {string}")
    public void sendVote(String userId, String action, String postAlias) {
        String postId = resolvePostId(postAlias);
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
        log.info("sent {} by '{}' on alias='{}' (postId='{}') → result: {}",
                action, userId, postAlias, postId, lastVoteResponse != null ? lastVoteResponse.getResult() : null);
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
     * Asserts that a vote document exists in the {@value ElasticsearchConstants#INDEX_USER_VOTES} index
     * for the given (userId, postId) pair with the expected action.
     * Document ID: {@code userId_postId}.
     */
    @And("a vote exists for user {string} on post {string} with action {string}")
    public void checkVoteExists(String userId, String postAlias, String expectedAction) throws IOException {
        String docId = userId + "_" + resolvePostId(postAlias);
        var response = esClient.get(g -> g.index(ElasticsearchConstants.INDEX_USER_VOTES).id(docId), UserVote.class);
        Assertions.assertTrue(response.found(), "Expected vote document '%s' to exist".formatted(docId));
        Assertions.assertNotNull(response.source(), "Vote document '%s' has no source".formatted(docId));
        Assertions.assertEquals(expectedAction, response.source().getAction(),
                "Vote action mismatch for document '%s'".formatted(docId));
    }

    /**
     * Asserts that NO vote document exists in the {@value ElasticsearchConstants#INDEX_USER_VOTES} index
     * for the given (userId, postId) pair — used after NOVOTE (Deleted) and NotFound transitions.
     */
    @And("no vote exists for user {string} on post {string}")
    public void checkVoteNotExists(String userId, String postAlias) throws IOException {
        String docId = userId + "_" + resolvePostId(postAlias);
        var response = esClient.get(g -> g.index(ElasticsearchConstants.INDEX_USER_VOTES).id(docId), UserVote.class);
        Assertions.assertFalse(response.found(),
                "Expected vote document '%s' to be absent, but it was found".formatted(docId));
    }

    /**
     * Asserts the current karma of a post in the {@value ElasticsearchConstants#INDEX_POSTS} index.
     * Uses the real-time Get API which reads from the primary shard directly,
     * bypassing the refresh cycle — no explicit refresh needed.
     */
    @And("post {string} karma is {long}")
    public void checkPostKarma(String postAlias, long expectedKarma) throws IOException {
        String postId = resolvePostId(postAlias);
        var response = esClient.get(g -> g.index(ElasticsearchConstants.INDEX_POSTS).id(postId), Post.class);
        Assertions.assertTrue(response.found(), "Post document '%s' not found".formatted(postId));
        Assertions.assertNotNull(response.source(), "Post document '%s' has no source".formatted(postId));
        Assertions.assertEquals(expectedKarma, response.source().karma(),
                "Karma mismatch for post '%s'".formatted(postId));
    }
}

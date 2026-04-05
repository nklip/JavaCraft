package dev.nklip.javacraft.elastic.app.controller;

import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import java.io.IOException;
import dev.nklip.javacraft.elastic.app.service.AdminService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AdminControllerTest {

    @Mock
    AdminService adminService;

    @Test
    public void testCreateUserVoteIndex() throws IOException {
        AdminController adminController = new AdminController(adminService);

        CreateIndexResponse createIndexResponse = CreateIndexResponse.of(builder -> builder
                .index("user-votes")
                .acknowledged(true)
                .shardsAcknowledged(true)
        );
        when(adminService.createUserVoteIndex()).thenReturn(new AdminService.IndexCreationResult(createIndexResponse, true));

        ResponseEntity<CreateIndexResponse> response = adminController.createUserVoteIndex();

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testCreateBooksIndex() throws IOException {
        AdminController adminController = new AdminController(adminService);

        CreateIndexResponse createIndexResponse = CreateIndexResponse.of(builder -> builder
                .index("books")
                .acknowledged(true)
                .shardsAcknowledged(true)
        );
        when(adminService.createBooksIndex()).thenReturn(new AdminService.IndexCreationResult(createIndexResponse, true));

        ResponseEntity<CreateIndexResponse> response = adminController.createBooksIndex();

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testCreateMoviesIndex() throws IOException {
        AdminController adminController = new AdminController(adminService);

        CreateIndexResponse createIndexResponse = CreateIndexResponse.of(builder -> builder
                .index("movies")
                .acknowledged(true)
                .shardsAcknowledged(true)
        );
        when(adminService.createMoviesIndex()).thenReturn(new AdminService.IndexCreationResult(createIndexResponse, true));

        ResponseEntity<CreateIndexResponse> response = adminController.createMoviesIndex();

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testCreateMusicIndex() throws IOException {
        AdminController adminController = new AdminController(adminService);

        CreateIndexResponse createIndexResponse = CreateIndexResponse.of(builder -> builder
                .index("music")
                .acknowledged(true)
                .shardsAcknowledged(true)
        );
        when(adminService.createMusicIndex()).thenReturn(new AdminService.IndexCreationResult(createIndexResponse, true));

        ResponseEntity<CreateIndexResponse> response = adminController.createMusicIndex();

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testCreateBooksIndexShouldReturnCreatedWhenAlreadyExists() throws IOException {
        AdminController adminController = new AdminController(adminService);

        CreateIndexResponse createIndexResponse = CreateIndexResponse.of(builder -> builder
                .index("books")
                .acknowledged(true)
                .shardsAcknowledged(true)
        );
        when(adminService.createBooksIndex()).thenReturn(new AdminService.IndexCreationResult(createIndexResponse, false));

        ResponseEntity<CreateIndexResponse> response = adminController.createBooksIndex();

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }
}

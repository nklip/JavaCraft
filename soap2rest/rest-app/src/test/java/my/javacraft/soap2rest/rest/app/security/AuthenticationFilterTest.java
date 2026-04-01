package my.javacraft.soap2rest.rest.app.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AuthenticationFilterTest {

    @AfterEach
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void testDoFilterInvokesChainWhenApiKeyIsValid() throws Exception {
        AuthenticationFilter filter = new AuthenticationFilter();
        FilterChain filterChain = mock(FilterChain.class);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        Assertions.assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void testDoFilterStopsChainWhenApiKeyIsInvalid() throws Exception {
        AuthenticationFilter filter = new AuthenticationFilter();
        FilterChain filterChain = mock(FilterChain.class);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "invalid-api-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        Assertions.assertEquals(401, response.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());
        Assertions.assertEquals("Invalid API Key", response.getContentAsString());
    }
}

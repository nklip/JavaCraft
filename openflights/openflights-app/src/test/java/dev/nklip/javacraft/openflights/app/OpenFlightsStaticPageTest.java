package dev.nklip.javacraft.openflights.app;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = {
                "spring.datasource.url=jdbc:h2:mem:openflights-app-web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        }
)
@AutoConfigureMockMvc
class OpenFlightsStaticPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void indexPageContainsSqlConsoleElements() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("SQL console")))
                .andExpect(content().string(Matchers.containsString("sql-input")))
                .andExpect(content().string(Matchers.containsString("execute-button")))
                .andExpect(content().string(Matchers.containsString("hide-show-button")))
                .andExpect(content().string(Matchers.containsString("/webjars/codemirror/5.65.20/lib/codemirror.css")))
                .andExpect(content().string(Matchers.containsString("/webjars/codemirror/5.65.20/lib/codemirror.js")))
                .andExpect(content().string(Matchers.containsString("/webjars/codemirror/5.65.20/mode/sql/sql.js")))
                .andExpect(content().string(Matchers.containsString("/css/index.css")))
                .andExpect(content().string(Matchers.containsString("/js/index.js")))
                .andExpect(content().string(Matchers.containsString("<div id=\"sql-output\"")))
                .andExpect(content().string(Matchers.containsString("sql-output")));
    }

    @Test
    void cssAssetIsServed() throws Exception {
        mockMvc.perform(get("/css/index.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("font-family: sans-serif")))
                .andExpect(content().string(Matchers.containsString("display: flex")))
                .andExpect(content().string(Matchers.containsString(".CodeMirror")))
                .andExpect(content().string(Matchers.containsString("#hide-show-button")))
                .andExpect(content().string(Matchers.containsString("margin-left: auto")))
                .andExpect(content().string(Matchers.containsString(".sql-result-toolbar")))
                .andExpect(content().string(Matchers.containsString(".sql-page-size-controls")))
                .andExpect(content().string(Matchers.containsString("justify-self: end")))
                .andExpect(content().string(Matchers.containsString(".sql-pagination")))
                .andExpect(content().string(Matchers.containsString(".sql-page-summary")))
                .andExpect(content().string(Matchers.containsString("justify-self: start")))
                .andExpect(content().string(Matchers.containsString(".sql-result-table")))
                .andExpect(content().string(Matchers.containsString(".sql-result-error")));
    }

    @Test
    void jsAssetIsServed() throws Exception {
        mockMvc.perform(get("/js/index.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("executeSql")))
                .andExpect(content().string(Matchers.containsString("/api/v1/openflights/admin/sql")))
                .andExpect(content().string(Matchers.containsString("CodeMirror.fromTextArea")))
                .andExpect(content().string(Matchers.containsString("text/x-pgsql")))
                .andExpect(content().string(Matchers.containsString("currentSql")))
                .andExpect(content().string(Matchers.containsString("renderSqlResult")))
                .andExpect(content().string(Matchers.containsString("renderTableResult")))
                .andExpect(content().string(Matchers.containsString("result.type === \"TABLE\"")))
                .andExpect(content().string(Matchers.containsString("result.rows.length")))
                .andExpect(content().string(Matchers.containsString("sql-result-message")))
                .andExpect(content().string(Matchers.containsString("sql-result-error")))
                .andExpect(content().string(Matchers.containsString("containsDataTable")))
                .andExpect(content().string(Matchers.containsString("hide-show-button")))
                .andExpect(content().string(Matchers.containsString("textContent = \"Hide\"")))
                .andExpect(content().string(Matchers.containsString("textContent = \"Show\"")))
                .andExpect(content().string(Matchers.containsString("pageSize")))
                .andExpect(content().string(Matchers.containsString("data-page-size")))
                .andExpect(content().string(Matchers.containsString("data-page")))
                .andExpect(content().string(Matchers.containsString("innerHTML")))
                .andExpect(content().string(Matchers.containsString("escapeHtml")));
    }

    @Test
    void codeMirrorAssetsAreServed() throws Exception {
        mockMvc.perform(get("/webjars/codemirror/5.65.20/lib/codemirror.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("CodeMirror")));

        mockMvc.perform(get("/webjars/codemirror/5.65.20/mode/sql/sql.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("CodeMirror.defineMode")));
    }

    @Test
    void apiDocsContainsLinkBackToHomePage() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("\"externalDocs\"")))
                .andExpect(content().string(Matchers.containsString("\"url\":\"/index.html\"")))
                .andExpect(content().string(Matchers.containsString("OpenFlights SQL Console")));
    }
}

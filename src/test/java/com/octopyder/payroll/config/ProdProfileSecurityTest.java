package com.octopyder.payroll.config;

import com.octopyder.payroll.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "prod"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ProdProfileSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("In prod profile: Swagger UI is disabled and returns 404")
    void swaggerUiDisabledInProd() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("In prod profile: OpenAPI docs are disabled and return 404")
    void openApiDocsDisabledInProd() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/v3/api-docs/swagger-config"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("In prod profile: /actuator/health returns status UP with zero details exposed")
    void actuatorHealthReturnsUpWithZeroDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist())
            .andExpect(content().string(not(containsString("diskSpace"))))
            .andExpect(content().string(not(containsString("mail"))))
            .andExpect(content().string(not(containsString("db"))));
    }
}

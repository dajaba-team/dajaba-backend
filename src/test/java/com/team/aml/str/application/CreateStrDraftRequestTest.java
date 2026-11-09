package com.team.aml.str.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.str.api.CreateStrDraftRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateStrDraftRequestTest {

    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserialize() throws Exception {
        String json = """
            {
                "caseVersion": 1
            }
            """;

        CreateStrDraftRequest request =
                objectMapper.readValue(json, CreateStrDraftRequest.class);

        System.out.println("결과 = " + request.caseVersion());

        assertEquals(1L, request.caseVersion());
    }
}

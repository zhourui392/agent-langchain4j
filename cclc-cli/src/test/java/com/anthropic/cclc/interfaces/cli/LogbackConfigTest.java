package com.anthropic.cclc.interfaces.cli;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigTest {

    @Test
    void should_ExposeMdcAndEnvironmentDrivenPackageLevels_InLogbackConfig() throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(Path.of("src/main/resources/logback.xml").toFile());

        String pattern = document.getElementsByTagName("pattern").item(0).getTextContent();
        assertThat(pattern)
                .contains("%X{session:-}")
                .contains("%X{turn:-}")
                .contains("%X{toolUseId:-}");

        Map<String, String> levelsByLogger = loggerLevels(document);
        assertThat(levelsByLogger)
                .containsEntry("com.anthropic.cclc.application", "${CCLC_LOG_LEVEL:-INFO}")
                .containsEntry("com.anthropic.cclc.infrastructure.tools", "${CCLC_LOG_LEVEL:-INFO}")
                .containsEntry("com.anthropic.cclc.infrastructure.llm", "${CCLC_LOG_LEVEL:-INFO}")
                .containsEntry("com.anthropic.cclc.infrastructure.diagnosis", "${CCLC_LOG_LEVEL:-INFO}");
    }

    private static Map<String, String> loggerLevels(Document document) {
        NodeList loggerNodes = document.getElementsByTagName("logger");
        Map<String, String> levels = new HashMap<>();
        for (int i = 0; i < loggerNodes.getLength(); i++) {
            Element logger = (Element) loggerNodes.item(i);
            levels.put(logger.getAttribute("name"), logger.getAttribute("level"));
        }
        return levels;
    }
}

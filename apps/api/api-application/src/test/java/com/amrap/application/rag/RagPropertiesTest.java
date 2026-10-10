package com.amrap.application.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RagPropertiesTest {

    @Test
    void unsetOrBlankTimezoneFallsBackToJvmDefault() {
        RagProperties properties = new RagProperties();
        assertThat(properties.zoneId()).isEqualTo(ZoneId.systemDefault());
        properties.setTimezone("  ");
        assertThat(properties.zoneId()).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void configuredTimezoneIsUsed() {
        RagProperties properties = new RagProperties();
        properties.setTimezone(" Asia/Kolkata ");
        assertThat(properties.zoneId()).isEqualTo(ZoneId.of("Asia/Kolkata"));
    }

    @Test
    void unknownTimezoneIsRejected() {
        RagProperties properties = new RagProperties();
        properties.setTimezone("Mars/Base");
        assertThatThrownBy(properties::zoneId).isInstanceOf(DateTimeException.class);
    }

    @Test
    void ingestKeyHasNoBuiltInDefault() {
        assertThat(new RagProperties().getIngestApiKey()).isNull();
    }
}

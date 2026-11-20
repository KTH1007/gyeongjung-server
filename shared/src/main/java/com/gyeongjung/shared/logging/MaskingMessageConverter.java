package com.gyeongjung.shared.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretMasker.mask(super.convert(event));
    }
}

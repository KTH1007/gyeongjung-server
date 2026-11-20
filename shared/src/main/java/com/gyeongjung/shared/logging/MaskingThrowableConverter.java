package com.gyeongjung.shared.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

public class MaskingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return SecretMasker.mask(super.throwableProxyToString(tp));
    }
}

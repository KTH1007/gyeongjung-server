package com.gyeongjung.shared;

import java.io.Serializable;
import org.springframework.http.HttpStatus;

public interface ErrorCode extends Serializable {

    HttpStatus status();

    String code();
}

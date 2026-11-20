package com.gyeongjung.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(errorCode.status(), e.getMessage());
        problem.setProperty("code", errorCode.code());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        LOG.error("Unhandled exception", e);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        problem.setProperty("code", CommonErrorCode.INTERNAL_ERROR.code());
        return problem;
    }

    // 프레임워크가 만드는 오류(405, 잘못된 본문, 없는 경로 등)도 같은 code 필드를 갖게 해서
    // 클라이언트가 모든 오류 응답을 한 가지 방식으로 처리하게 한다.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ResponseEntity<Object> response =
                super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("code", codeOf(statusCode));
        }
        return response;
    }

    private static String codeOf(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return CommonErrorCode.INTERNAL_ERROR.code();
        }
        if (status.isSameCodeAs(HttpStatus.BAD_REQUEST)) {
            return CommonErrorCode.INVALID_REQUEST.code();
        }
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return CommonErrorCode.NOT_FOUND.code();
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? CommonErrorCode.INVALID_REQUEST.code() : resolved.name();
    }
}

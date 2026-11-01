package com.echomind.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidInput(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail oversizedUpload(MaxUploadSizeExceededException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超过 10MB 限制");
    }
}

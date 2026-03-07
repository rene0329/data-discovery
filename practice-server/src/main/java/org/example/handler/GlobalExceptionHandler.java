package org.example.handler;

import lombok.extern.slf4j.Slf4j;
import org.example.constant.MessageConstant;
import org.example.exception.BaseException;
import org.example.result.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;

/**
 * 全局异常处理器，处理项目中抛出的业务异常
 *
 * @RestControllerAdvice：定义一个全局的异常处理器。 通过将 @ExceptionHandler 方法放置 在标记了 @RestControllerAdvice 的类中，
 * 你可以捕获应用程序中 所有控制器 抛出的异常，而不需要在每个控制器中重复定义异常处理逻辑。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 捕获业务异常
     *
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result exceptionHandler(BaseException ex) {
        log.info("异常信息：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 处理SQL异常（example：添加用户时 username 重复，不唯一）: Duplicate entry ...
     *
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result exceptionHandler(SQLIntegrityConstraintViolationException ex) {
        String message = ex.getMessage();
        if (message.contains("Duplicate entry")) {
            String[] split = message.split(" ");
            String username = split[2];
            String msg = username + MessageConstant.ALREADY_EXISTS;
            return Result.error(msg);
        } else {
            return Result.error(MessageConstant.UNKNOWN_ERROR);
        }
    }
}

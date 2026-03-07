package org.example.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.example.annotation.AutoFill;
import org.example.constant.AutoFillConstant;
import org.example.context.BaseContext;
import org.example.enumeration.OperationType;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 自定义切面类，实现公共字段自动填充处理逻辑
 */
@Component //被spring容器管理
@Slf4j
@Aspect //标识当前类是一个AOP类
public class AutoFillAspect {

    @Pointcut("execution(* org.example.mapper.*.*(..)) && @annotation(org.example.annotation.AutoFill)")
    public void  autoFillPointCut(){}

    @Before("autoFillPointCut()")
    public void autoFill(JoinPoint joinPoint) {
        log.info("开始公共字段自动填充");

        // 1、获取到当前 被拦截的方法上的 数据操作类型
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();// 方法签名对象
        AutoFill autoFill = signature.getMethod().getAnnotation(AutoFill.class);// 获得方法上的注解对象
        OperationType operationType = autoFill.value(); // 获得数据库操作类型

        /**
         * 获取到当前 被拦截的方法的参数 --- 实体对象
         * 一开始我们使用的是 DTO，但是我们传入mapper层的是 Entity，因此我们需要获取Entity，然后为其自动填充一些属性值
         */
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return;
        }
        Object entity = args[0];

        // 准备赋值的数据
        LocalDateTime now = LocalDateTime.now();
        Long currentId = BaseContext.getCurrentId();

        // 根据不同的操作类型，为对应的属性通过 反射 来赋值
        if (operationType == OperationType.INSERT) {
            // 插入操作，需要为4个公共字段赋值
            try {
                /**
                 * 获取方法对象
                 *   1、getDeclaredMethod(String name, Class<?>... parameterTypes)：通过方法名和参数类型，获取 entity 类中的某个方法的 Method 对象。
                 *     我们使用 AutoFillConstant 来设置 entity 类中的方法名，即AutoFillConstant.SET_CREATE_TIME这个常量代表方法名
                 *   作用：是通过 反射 获取 entity 类中名为 setUpdateTime 和 setUpdateUser 的等方法对象。
                 */
                Method setCreateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_TIME, LocalDateTime.class);
                Method setCreateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_USER, Long.class);
                Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
                Method setUpdateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);

                /**
                 * 通过反射为对象属性赋值
                 *   1、invoke(Object obj, Object... args)：调用由 Method 对象表示的方法。
                 */
                setCreateTime.invoke(entity, now);
                setCreateUser.invoke(entity, currentId);
                setUpdateTime.invoke(entity, now);
                setUpdateUser.invoke(entity, currentId);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } else if (operationType == OperationType.UPDATE) {
            // 为2个公共字段赋值
            try {
                Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
                Method setUpdateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);

                // 通过反射为对象属性赋值
                setUpdateTime.invoke(entity, now);
                setUpdateUser.invoke(entity, currentId);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}



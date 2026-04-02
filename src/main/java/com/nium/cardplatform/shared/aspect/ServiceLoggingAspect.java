package com.nium.cardplatform.shared.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * Cross-cutting logging aspect applied to every public method in any
 * class annotated with @Service across all modules.
 * <p>Uses try/finally so the timing log always runs regardless of whether
 * the method completed normally or threw. The exception propagates naturally
 * without being caught and rethrown — no risk of accidentally swallowing it.
 * <p>The boolean `success` flag lets the finally block distinguish between
 * a normal completion and a throw, without needing a catch block at all.
 * <p>Logs at DEBUG on entry/exit so production noise is minimal (INFO default),
 * but full method traces are available when DEBUG is enabled for diagnosis.
 * Any method exceeding 500ms is logged at WARN to surface slow operations.
 */
@Slf4j
@Aspect
@Component
public class ServiceLoggingAspect {

    @Pointcut("within(@org.springframework.stereotype.Service *)")
    public void serviceBean() {
    }

    @Pointcut("execution(public * *(..))")
    public void publicMethod() {
    }

    @Around("serviceBean() && publicMethod()")
    public Object logServiceCall(ProceedingJoinPoint pjp) throws Throwable {
        String className = pjp.getTarget().getClass().getSimpleName();
        String methodName = pjp.getSignature().getName();
        long start = System.currentTimeMillis();
        boolean success = false;

        log.debug(">> {}.{}", className, methodName);

        try {
            Object result = pjp.proceed();
            success = true;
            return result;
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            if (success) {
                if (elapsed > 500) {
                    log.warn("<< {}.{}() completed in {}ms (SLOW)", className, methodName, elapsed);
                } else {
                    log.debug("<< {}.{}() completed in {}ms", className, methodName, elapsed);
                }
            } else {
                log.warn("<< {}.{}() threw an exception after {}ms", className, methodName, elapsed);
            }
        }
    }
}

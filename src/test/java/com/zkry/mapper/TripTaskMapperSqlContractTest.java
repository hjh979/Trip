package com.zkry.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class TripTaskMapperSqlContractTest {

    private static final Pattern MYBATIS_PARAMETER = Pattern.compile("#\\{([a-zA-Z0-9_]+)");

    @Test
    void heartbeatSqlOnlyReferencesDeclaredParameters() throws Exception {
        Method heartbeat = Arrays.stream(TripTaskMapper.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("heartbeat"))
            .findFirst()
            .orElseThrow();

        Set<String> declaredParameters = new LinkedHashSet<>();
        for (Parameter parameter : heartbeat.getParameters()) {
            Param annotation = parameter.getAnnotation(Param.class);
            if (annotation != null) declaredParameters.add(annotation.value());
        }

        Update update = heartbeat.getAnnotation(Update.class);
        Set<String> referencedParameters = new LinkedHashSet<>();
        Matcher matcher = MYBATIS_PARAMETER.matcher(String.join("\n", update.value()));
        while (matcher.find()) referencedParameters.add(matcher.group(1));

        assertThat(referencedParameters).isSubsetOf(declaredParameters);
    }
}

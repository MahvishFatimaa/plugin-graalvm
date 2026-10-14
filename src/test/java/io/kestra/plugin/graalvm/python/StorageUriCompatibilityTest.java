package io.kestra.plugin.graalvm.python;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
class StorageUriCompatibilityTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void repeatedOpensShareLocalEditsWithoutUpdatingStorage() throws Exception {
        RunContext uploadContext = runContextFactory.of();
        var uri = uploadContext.storage().putFile(
            new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)), UUID.randomUUID() + ".txt"
        );
        RunContext runContext = runContextFactory.of(Map.of("inputs", Map.of("uri", uri.toString())));
        Eval task = Eval.builder()
            .id(UUID.randomUUID().toString())
            .type(Eval.class.getName())
            .script(Property.ofExpression("""
                with open('{{ inputs.uri }}') as source:
                    before = source.read()
                with open('{{ inputs.uri }}', 'a') as target:
                    target.write(' edited')
                with open('{{ inputs.uri }}') as source:
                    after = source.read()
                opaque = '{{ inputs.uri }}'
                with open('local.txt', 'w') as target:
                    target.write('normal local file')
                with open('local.txt') as source:
                    local = source.read()
                """))
            .outputs(Property.ofValue(List.of("before", "after", "opaque", "local")))
            .build();
        try {
            var output = task.run(runContext).getOutputs();
            assertThat(output.get("before"), is("original"));
            assertThat(output.get("after"), is("original edited"));
            assertThat(output.get("opaque"), is(uri.toString()));
            assertThat(output.get("local"), is("normal local file"));
            try (var original = uploadContext.storage().getFile(uri)) {
                assertThat(new String(original.readAllBytes(), StandardCharsets.UTF_8), is("original"));
            }
        } finally {
            runContext.cleanup();
        }
    }

    @Test
    void ignoresStorageUriInComment() throws Exception {
        RunContext runContext = runContextFactory.of();
        Eval task = Eval.builder()
            .id(UUID.randomUUID().toString())
            .type(Eval.class.getName())
            .script(Property.ofValue("# kestra:///" + UUID.randomUUID() + ".csv\nanswer = 42"))
            .outputs(Property.ofValue(List.of("answer")))
            .build();

        try {
            assertThat(task.run(runContext).getOutputs().get("answer"), is(42));
        } finally {
            runContext.cleanup();
        }
    }

    @Test
    void preservesRenderedOpaqueStorageUri() throws Exception {
        String uri = "kestra:///" + UUID.randomUUID() + ".csv";
        RunContext runContext = runContextFactory.of(Map.of("inputs", Map.of("uri", uri)));
        Eval task = Eval.builder()
            .id(UUID.randomUUID().toString())
            .type(Eval.class.getName())
            .script(Property.ofExpression("uri = '{{ inputs.uri }}'"))
            .outputs(Property.ofValue(List.of("uri")))
            .build();

        try {
            assertThat(task.run(runContext).getOutputs().get("uri"), is(uri));
        } finally {
            runContext.cleanup();
        }
    }
}

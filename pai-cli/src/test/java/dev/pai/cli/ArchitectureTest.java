/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Machine-checked architecture rules: 00-architecture §2 and 01-code-style §1.5.
 */
class ArchitectureTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("dev.pai");

    private static final JavaClasses ALL = new ClassFileImporter().importPackages("dev.pai");

    private static final Set<String> SERVICE_INTERFACES =
        Set.of("Provider", "SessionStore", "Renderer", "Summarizer", "TokenEstimator", "Approver", "McpTransport");

    private static final Pattern CJK = Pattern.compile("[\\x{4e00}-\\x{9fff}]");

    @Test
    void model_depends_only_on_itself_jdk_jackson_and_slf4j() {
        ArchRule rule = classes().that().resideInAPackage("dev.pai.model..").should().onlyDependOnClassesThat()
            .resideInAnyPackage("dev.pai.model..", "java..", "com.fasterxml.jackson..", "org.slf4j..");
        rule.check(MAIN);
    }

    @Test
    void core_does_not_depend_on_outer_layers() {
        ArchRule rule = noClasses().that().resideInAPackage("dev.pai.core..").should().dependOnClassesThat()
            .resideInAnyPackage("dev.pai.tui..", "dev.pai.cli..", "dev.pai.ext..", "dev.pai.tools..");
        rule.check(MAIN);
    }

    @Test
    void tools_and_extensions_do_not_depend_on_cli_or_tui() {
        ArchRule rule = noClasses().that().resideInAnyPackage("dev.pai.tools..", "dev.pai.ext..").should()
            .dependOnClassesThat().resideInAnyPackage("dev.pai.cli..", "dev.pai.tui..");
        rule.check(MAIN);
    }

    @Test
    void core_internal_packages_are_not_used_outside_core() {
        ArchRule rule = noClasses().that().resideOutsideOfPackage("dev.pai.core..").should().dependOnClassesThat()
            .resideInAPackage("dev.pai.core..internal..");
        rule.check(MAIN);
    }

    @Test
    void public_core_and_model_types_are_interface_record_enum_or_final() {
        ArchRule rule = classes().that().resideInAnyPackage("dev.pai.model..", "dev.pai.core..").and().arePublic().and()
            .areTopLevelClasses().should(beInterfaceRecordEnumOrFinal());
        rule.check(MAIN);
    }

    @Test
    void no_forbidden_name_fragments() {
        ArchRule rule = noClasses().should(haveForbiddenName());
        rule.check(MAIN);
    }

    @Test
    void enums_end_with_enum() {
        classes().that().areEnums().should().haveSimpleNameEndingWith("Enum").check(MAIN);
    }

    @Test
    void service_implementations_end_with_impl() {
        ArchRule rule = classes().that(implementServiceInterface()).and().areNotInterfaces().and()
            .areNotAnonymousClasses().should().haveSimpleNameEndingWith("Impl");
        rule.check(ALL);
    }

    @Test
    void abstract_classes_and_exceptions_follow_naming() {
        classes().that().haveModifier(JavaModifier.ABSTRACT).and().areNotInterfaces().and().areNotEnums().should()
            .haveSimpleNameStartingWith("Abstract").orShould().haveSimpleNameStartingWith("Base").check(MAIN);
        classes().that().areAssignableTo(Throwable.class).should().haveSimpleNameEndingWith("Exception").check(MAIN);
    }

    @Test
    void test_classes_end_with_test() {
        JavaClasses tests = new ClassFileImporter().withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages("dev.pai");
        classes().that().areTopLevelClasses().and(containTestMethods()).should().haveSimpleNameEndingWith("Test")
            .check(tests);
    }

    @Test
    void package_names_are_lowercase_single_words() {
        classes().should().resideInAPackage("dev.pai..").andShould(haveLowercasePackage()).check(MAIN);
    }

    @Test
    void no_mutable_static_fields() {
        fields().that().areStatic().and().areDeclaredInClassesThat().resideInAPackage("dev.pai..").and(notSynthetic())
            .should().beFinal().check(MAIN);
    }

    @Test
    void constant_fields_are_upper_snake_case() {
        fields().that().areStatic().and().areFinal().and(primitiveOrString()).and(notSynthetic())
            .should(haveUpperSnakeCaseName()).check(MAIN);
    }

    @Test
    void core_layers_avoid_legacy_date_and_stdout() {
        noClasses().that().resideInAnyPackage("dev.pai.model..", "dev.pai.core..").should().dependOnClassesThat()
            .haveFullyQualifiedName("java.util.Date").orShould().dependOnClassesThat()
            .haveFullyQualifiedName("java.text.SimpleDateFormat").orShould().accessField(System.class, "out").orShould()
            .accessField(System.class, "err").check(MAIN);
    }

    @Test
    void core_layers_have_no_cjk_literals() {
        Path root = projectRoot();
        List<String> hits = new ArrayList<>();
        for (String module : List.of("pai-model", "pai-core")) {
            Path src = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> collectCjk(p, hits));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(hits).isEmpty();
    }

    private static void collectCjk(Path file, List<String> hits) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (CJK.matcher(lines.get(i)).find()) {
                    hits.add(file + ":" + (i + 1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path projectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("pai-core"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("project root").isNotNull();
        return dir;
    }

    private static ArchCondition<JavaClass> beInterfaceRecordEnumOrFinal() {
        return new ArchCondition<>("be an interface, record, enum or final class") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean ok = item.isInterface() || item.isEnum() || item.isRecord()
                    || item.getModifiers().contains(JavaModifier.FINAL);
                events.add(new SimpleConditionEvent(item, ok, item.getName() + " is not interface/record/enum/final"));
            }
        };
    }

    private static ArchCondition<JavaClass> haveForbiddenName() {
        Pattern forbidden = Pattern.compile("Legacy|Adapter|Bridge|Compat|(Util|Utils|Helper|Manager)$");
        Set<String> allowed = Set.of("OpenAiCompatProviderImpl", "McpServerManager");
        return new ArchCondition<>("have a forbidden name fragment") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String name = item.getSimpleName();
                boolean bad = !allowed.contains(name) && forbidden.matcher(name).find();
                events.add(new SimpleConditionEvent(item, bad, item.getName() + " has a forbidden name"));
            }
        };
    }

    private static DescribedPredicate<JavaClass> implementServiceInterface() {
        return new DescribedPredicate<>("implement a service interface") {
            @Override
            public boolean test(JavaClass input) {
                return input.getAllRawInterfaces().stream().anyMatch(
                    i -> i.getPackageName().startsWith("dev.pai") && SERVICE_INTERFACES.contains(i.getSimpleName()));
            }
        };
    }

    private static DescribedPredicate<JavaClass> containTestMethods() {
        return new DescribedPredicate<>("contain @Test methods") {
            @Override
            public boolean test(JavaClass input) {
                return input.getMethods().stream().anyMatch(m -> m.isAnnotatedWith(Test.class));
            }
        };
    }

    private static ArchCondition<JavaClass> haveLowercasePackage() {
        Pattern pkg = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*");
        return new ArchCondition<>("have an all-lowercase package name") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean ok = pkg.matcher(item.getPackageName()).matches();
                events.add(new SimpleConditionEvent(item, ok, item.getPackageName() + " is not lowercase"));
            }
        };
    }

    private static DescribedPredicate<JavaField> notSynthetic() {
        return new DescribedPredicate<>("not synthetic") {
            @Override
            public boolean test(JavaField input) {
                return !input.getModifiers().contains(JavaModifier.SYNTHETIC) && !input.getName().contains("$");
            }
        };
    }

    private static DescribedPredicate<JavaField> primitiveOrString() {
        return new DescribedPredicate<>("primitive or String typed") {
            @Override
            public boolean test(JavaField input) {
                JavaClass type = input.getRawType();
                return type.isPrimitive() || "java.lang.String".equals(type.getName());
            }
        };
    }

    private static ArchCondition<JavaField> haveUpperSnakeCaseName() {
        Pattern upper = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");
        return new ArchCondition<>("have an UPPER_SNAKE_CASE name") {
            @Override
            public void check(JavaField item, ConditionEvents events) {
                boolean ok = upper.matcher(item.getName()).matches();
                events.add(new SimpleConditionEvent(item, ok, item.getFullName() + " is not UPPER_SNAKE_CASE"));
            }
        };
    }
}

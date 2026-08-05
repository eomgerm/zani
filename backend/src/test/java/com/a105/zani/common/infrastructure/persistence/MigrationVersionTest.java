package com.a105.zani.common.infrastructure.persistence;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이그레이션 파일명이 Flyway 규약을 지키고 버전이 겹치지 않는지 본다.
 *
 * <p><b>버전이 겹쳐도 git 은 아무 말을 하지 않는다.</b> 파일명이 다르니 충돌이 없고, 두 MR 이 각자 깨끗하게 머지된다. 터지는 곳은 기동 시점의 Flyway 이고,
 * {@code flywayInitializer} 가 죽으면 그것에 의존하는 {@code entityManagerFactory} 가 함께 죽어 <b>모든 {@code @SpringBootTest} 가
 * 무너진다.</b> 증상이 원인에서 멀어서 처음 보면 원인을 찾는 데 시간이 걸린다.
 *
 * <p>dev 가 실제로 두 번 이렇게 멈췄다.
 *
 * <ul>
 *   <li>V13 — S15P11A105-247 과 267 이 각각 다른 이름으로 13 을 썼다
 *   <li>V16·V17 — S15P11A105-249 와 250 이 각각 다른 이름으로 16·17 을 썼다
 * </ul>
 *
 * <p>두 번 다 각자의 MR 안에서는 보이지 않았고 머지된 뒤에야 드러났다. 번호를 손으로 맞추는 방식이 반복해서 실패했다는 뜻이라, 세는 일은 기계에 맡긴다.
 *
 * <p>DB 를 띄우지 않고 파일명만 본다. 그래야 각 MR 의 파이프라인에서 빠르게 걸리고, 머지되기 <b>전에</b> 드러난다.
 */
class MigrationVersionTest {

    /** Flyway 버전 마이그레이션 파일명 규약. {@code spring.flyway.validate-migration-naming=true} 가 기동 시점에 보는 것과 같은 형태다. */
    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V(?<version>\\d+)__(?<description>[A-Za-z0-9_]+)\\.sql$");

    private static final String MIGRATION_LOCATION = "classpath*:db/migration/*.sql";

    @Test
    void everyMigrationFilenameFollowsTheFlywayConvention() {
        List<String> violations = filenames().stream()
                .filter(filename -> !VERSIONED_MIGRATION.matcher(filename).matches())
                .toList();

        assertThat(violations)
                .as("db/migration 은 V<버전>__<설명>.sql 만 담는다. 되풀이 마이그레이션(R__)은 db/seed 에 둔다")
                .isEmpty();
    }

    @Test
    void noTwoMigrationsShareAVersion() {
        Map<Integer, List<String>> byVersion = new LinkedHashMap<>();
        for (String filename : filenames()) {
            Matcher matcher = VERSIONED_MIGRATION.matcher(filename);
            if (!matcher.matches()) {
                // 규약 위반은 위 테스트가 따로 말한다. 여기서 함께 실패시키면 원인이 둘 섞인다.
                continue;
            }
            byVersion
                    .computeIfAbsent(Integer.parseInt(matcher.group("version")), version -> new ArrayList<>())
                    .add(filename);
        }

        List<String> collisions = byVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "V%d ← %s".formatted(entry.getKey(), String.join(", ", entry.getValue())))
                .toList();

        assertThat(collisions).as("""
                        같은 버전을 쓰는 마이그레이션이 둘 이상이다. 기동에서 \
                        "Found more than one migration with version N" 으로 죽는다. \
                        나중에 만든 쪽을 비어 있는 다음 번호로 옮겨라 — 파일명만 바꾸면 되고 내용은 그대로다.""").isEmpty();
    }

    /** 클래스패스에서 읽는다. 테스트를 어느 디렉터리에서 돌리든 같은 것을 본다. */
    private List<String> filenames() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(MIGRATION_LOCATION);
            List<String> filenames =
                    Arrays.stream(resources).map(Resource::getFilename).toList();
            assertThat(filenames)
                    .as("마이그레이션을 하나도 못 찾았다면 경로가 바뀐 것이다 — 이 테스트가 조용히 통과하면 안 된다")
                    .isNotEmpty();
            return filenames;
        } catch (IOException exception) {
            throw new IllegalStateException("마이그레이션 목록을 읽지 못했다: " + MIGRATION_LOCATION, exception);
        }
    }
}

package com.viralground.backend.repository;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect="
})
class CreatorInstagramConnectionRepositoryTest {

    @Autowired
    CreatorInstagramConnectionRepository repository;

    @Autowired
    TestEntityManager em;

    @Test
    void creatorId_로_연결을_저장하고_조회한다() {
        // given
        repository.save(CreatorInstagramConnection.builder()
                .creatorId(42)
                .provider("PHYLLO")
                .status(ConnectionStatus.CONNECTED)
                .igUsername("viral.creator")
                .connectedAt(LocalDateTime.now())
                .build());

        // when
        Optional<CreatorInstagramConnection> found = repository.findByCreatorId(42);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getIgUsername()).isEqualTo("viral.creator");
        assertThat(found.get().getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
    }

    @Test
    void creatorId_는_유니크_제약을_가진다() {
        // given — 같은 creatorId 로 2건 저장 시도
        repository.saveAndFlush(CreatorInstagramConnection.builder()
                .creatorId(7).provider("PHYLLO").status(ConnectionStatus.PENDING).build());

        // when / then
        assertThatThrownBy(() -> repository.saveAndFlush(CreatorInstagramConnection.builder()
                .creatorId(7).provider("PHYLLO").status(ConnectionStatus.PENDING).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void status_로_연결_목록을_조회한다() {
        // given
        repository.save(CreatorInstagramConnection.builder()
                .creatorId(1).provider("PHYLLO").status(ConnectionStatus.CONNECTED).build());
        repository.save(CreatorInstagramConnection.builder()
                .creatorId(2).provider("PHYLLO").status(ConnectionStatus.CONNECTED).build());
        repository.save(CreatorInstagramConnection.builder()
                .creatorId(3).provider("PHYLLO").status(ConnectionStatus.DISCONNECTED).build());

        // when
        List<CreatorInstagramConnection> connected = repository.findByStatus(ConnectionStatus.CONNECTED);

        // then
        assertThat(connected).hasSize(2)
                .extracting(CreatorInstagramConnection::getCreatorId)
                .containsExactlyInAnyOrder(1, 2);
    }
}

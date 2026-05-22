package com.viralground.backend.repository;

import com.viralground.backend.entity.ContactRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContactRequestRepository extends JpaRepository<ContactRequest, Integer> {

    List<ContactRequest> findAllByOrderByCreatedAtDesc();
}

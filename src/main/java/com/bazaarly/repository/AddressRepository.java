package com.bazaarly.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bazaarly.modal.Address;

public interface AddressRepository extends JpaRepository<Address, Long> {

}

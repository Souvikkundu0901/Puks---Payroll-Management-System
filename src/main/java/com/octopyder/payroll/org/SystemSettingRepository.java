package com.octopyder.payroll.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {

    List<SystemSetting> findByKeyStartingWith(String prefix);
}

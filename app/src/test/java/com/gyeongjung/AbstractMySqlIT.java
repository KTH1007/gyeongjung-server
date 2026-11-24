package com.gyeongjung;

import com.gyeongjung.shared.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "logging.file.name=build/test-logs/gyeongjung.log")
public abstract class AbstractMySqlIT extends MySqlContainerSupport {}

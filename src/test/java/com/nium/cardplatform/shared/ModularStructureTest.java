package com.nium.cardplatform.shared;

import com.nium.cardplatform.CardPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularStructureTest {

    ApplicationModules modules = ApplicationModules.of(CardPlatformApplication.class);

    @Test
    void verifyModularStructure() {
        modules.verify();
    }

    @Test
    void writeDocumentationSnippets() {
        new Documenter(modules).writeModulesAsPlantUml();
    }
}

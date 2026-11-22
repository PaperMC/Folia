package io.papermc.paper.spi;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectPackages({
    "io.papermc.paper.threadedregions.spi",
    "io.papermc.paper.spi",
    "ca.spottedleaf.moonrise.spi"
})
public class PaperSpiTestSuite {
}

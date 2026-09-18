package com.gsc.spacecraft;

import static org.assertj.core.api.Assertions.assertThat;

import com.gsc.spacecraft.Farm.Verdict;
import org.junit.jupiter.api.Test;

class FarmTest {

    @Test
    void acceptsOnlyTheExpectedFrame() {
        Farm farm = new Farm(1);

        assertThat(farm.receive(1)).isEqualTo(Verdict.ACCEPTED);
        assertThat(farm.receive(2)).isEqualTo(Verdict.ACCEPTED);
        assertThat(farm.clcw().vr()).isEqualTo(3);
    }

    @Test
    void retransmittedFrameIsDiscardedButStillAcknowledged() {
        Farm farm = new Farm(1);
        farm.receive(1);

        assertThat(farm.receive(1)).isEqualTo(Verdict.DUPLICATE);
        assertThat(farm.clcw().vr()).isEqualTo(2);
    }

    @Test
    void gapIsRejectedAndFlagsRetransmit() {
        Farm farm = new Farm(1);
        farm.receive(1);

        assertThat(farm.receive(3)).isEqualTo(Verdict.OUT_OF_SEQUENCE);
        assertThat(farm.clcw().vr()).isEqualTo(2);
        assertThat(farm.clcw().retransmit()).isTrue();

        assertThat(farm.receive(2)).isEqualTo(Verdict.ACCEPTED);
        assertThat(farm.clcw().retransmit()).isFalse();
    }
}

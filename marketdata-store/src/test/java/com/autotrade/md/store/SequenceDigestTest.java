package com.autotrade.md.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SequenceDigestTest {

    @Test
    void sameRowsInSameOrderGiveSameDigest() {
        assertThat(digest(1, 10, 2, 20).sameAs(digest(1, 10, 2, 20))).isTrue();
    }

    @Test
    void missingReorderedOrChangedRowsChangeTheDigest() {
        SequenceDigest.Result base = digest(1, 10, 2, 20, 3, 30);

        assertThat(digest(1, 10, 3, 30).sameAs(base)).isFalse();
        assertThat(digest(2, 20, 1, 10, 3, 30).sameAs(base)).isFalse();
        assertThat(digest(1, 10, 2, 21, 3, 30).sameAs(base)).isFalse();
    }

    @Test
    void reportsRangeAndOrdering() {
        SequenceDigest.Result result = digest(5, 1, 9, 2, 7, 3);

        assertThat(result.count()).isEqualTo(3);
        assertThat(result.firstSequence()).isEqualTo(5);
        assertThat(result.lastSequence()).isEqualTo(7);
        assertThat(result.strictlyIncreasing()).isFalse();
    }

    @Test
    void hash64TakesTheFirstEightBytes() {
        assertThat(SequenceDigest.hash64("f7b960a71986a20b51f231cc840d617a366068ec7aee72edbb32e83200d4915e"))
                .isEqualTo(0xf7b960a71986a20bL);
    }

    private static SequenceDigest.Result digest(long... pairs) {
        SequenceDigest digest = new SequenceDigest();
        for (int i = 0; i < pairs.length; i += 2) {
            digest.add(pairs[i], pairs[i + 1]);
        }
        return digest.result();
    }
}

package com.zkry.integration.amap.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zkry.domain.dto.map.MapPoint;
import org.junit.jupiter.api.Test;

class AmapCoordinateNormalizerTest {

    @Test
    void swapsReversedHangzhouCoordinate() {
        AmapCoordinateNormalizer.NormalizedPoint result = AmapCoordinateNormalizer.normalize(
            new MapPoint(30.240826, 120.101406)
        );

        assertThat(result.valid()).isTrue();
        assertThat(result.swapped()).isTrue();
        assertThat(result.point().longitude()).isEqualTo(120.101406);
        assertThat(result.point().latitude()).isEqualTo(30.240826);
    }

    @Test
    void keepsCorrectLongitudeLatitudeOrder() {
        AmapCoordinateNormalizer.NormalizedPoint result = AmapCoordinateNormalizer.normalize(
            new MapPoint(120.101406, 30.240826)
        );

        assertThat(result.valid()).isTrue();
        assertThat(result.swapped()).isFalse();
        assertThat(result.point()).isEqualTo(new MapPoint(120.101406, 30.240826));
    }

    @Test
    void rejectsCoordinateThatCannotBeCorrectedSafely() {
        AmapCoordinateNormalizer.NormalizedPoint result = AmapCoordinateNormalizer.normalize(
            new MapPoint(250.0, 95.0)
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.swapped()).isFalse();
    }
}

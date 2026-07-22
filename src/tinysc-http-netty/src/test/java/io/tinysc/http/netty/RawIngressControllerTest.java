package io.tinysc.http.netty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawIngressControllerTest {
    @Test
    void boundsInitialAndIncrementalReservationsAndRecovers() {
        RawIngressController controller = new RawIngressController(10L);
        RawIngressController.Lease first = controller.tryAcquire(6L);

        assertNotNull(first);
        assertEquals(1L, controller.activeReservations());
        assertEquals(6L, controller.reservedBytes());
        assertNull(controller.tryAcquire(5L));
        assertEquals(1L, controller.rejectedReservations());

        RawIngressController.Lease second = controller.tryAcquire(0L);
        assertNotNull(second);
        assertTrue(second.tryReserve(4L));
        assertFalse(second.tryReserve(1L));
        assertEquals(2L, controller.rejectedReservations());
        assertEquals(10L, controller.reservedBytes());

        first.close();
        first.close();
        second.close();
        assertEquals(0L, controller.activeReservations());
        assertEquals(0L, controller.reservedBytes());
    }

    @Test
    void zeroByteReservationsDoNotChangeByteOrRejectionCounters() {
        RawIngressController controller = new RawIngressController(1L);
        RawIngressController.Lease lease = controller.tryAcquire(0L);

        assertNotNull(lease);
        assertEquals(1L, controller.activeReservations());
        for (int index = 0; index < 100_000; index++) {
            assertTrue(lease.tryReserve(0L));
        }
        assertEquals(0L, controller.reservedBytes());
        assertEquals(0L, controller.rejectedReservations());

        lease.close();
        lease.close();
        assertFalse(lease.tryReserve(0L));
        assertEquals(0L, controller.activeReservations());
        assertEquals(0L, controller.reservedBytes());
        assertEquals(0L, controller.rejectedReservations());
    }
}

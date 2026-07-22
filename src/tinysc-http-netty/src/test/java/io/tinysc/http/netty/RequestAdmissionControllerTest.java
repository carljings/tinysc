package io.tinysc.http.netty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestAdmissionControllerTest {
    @Test
    void boundsConnectionsAndRecoversAfterRelease() {
        RequestAdmissionController controller = new RequestAdmissionController(1, 2L, 10L);
        RequestAdmissionController.ConnectionLease first = controller.tryAcquireConnection();

        assertNotNull(first);
        assertNull(controller.tryAcquireConnection());
        assertEquals(1L, controller.activeConnections());
        assertEquals(1L, controller.rejectedConnections());

        first.close();
        first.close();
        RequestAdmissionController.ConnectionLease recovered =
                controller.tryAcquireConnection();
        assertNotNull(recovered);
        recovered.close();
        assertEquals(0L, controller.activeConnections());
    }

    @Test
    void boundsRequestsAndBytesAndReleasesOnlyOnce() {
        RequestAdmissionController controller = new RequestAdmissionController(1, 2L, 10L);
        RequestAdmissionController.RequestLease first = controller.tryAcquireRequest(6L);

        assertNotNull(first);
        assertNull(controller.tryAcquireRequest(5L));
        assertEquals(1L, controller.activeRequests());
        assertEquals(6L, controller.activeRequestBytes());
        assertEquals(1L, controller.rejectedRequestBytes());

        RequestAdmissionController.RequestLease second = controller.tryAcquireRequest(4L);
        assertNotNull(second);
        assertNull(controller.tryAcquireRequest(0L));
        assertEquals(1L, controller.rejectedRequests());

        first.close();
        first.close();
        second.close();
        assertEquals(0L, controller.activeRequests());
        assertEquals(0L, controller.activeRequestBytes());
    }
}

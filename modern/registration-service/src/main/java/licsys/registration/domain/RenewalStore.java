package licsys.registration.domain;

import java.util.List;

public interface RenewalStore {

    void save(Renewal renewal);

    List<Renewal> findAll();

    /** Deletes every renewal and returns how many were deleted. */
    int deleteAll();
}

package com.team.aml.cases.application;

import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * users.id → login_id·display_name. 목록 한 페이지(최대 100건)의 담당자는 몇 명뿐이라 id 마다 한 번씩 읽는다.
 * cases → user 는 읽기만 (02_structure §3).
 */
@Component
class UserRefs {

    private final UserRepository users;

    UserRefs(UserRepository users) {
        this.users = users;
    }

    /** null id 는 건너뛴다. 없는 사용자는 지도에 넣지 않는다(FK 가 있어 실제로는 없음). */
    Map<Long, UserRef> of(Collection<Long> ids) {
        Map<Long, UserRef> refs = new HashMap<>();
        ids.stream().filter(Objects::nonNull).distinct().forEach(id ->
                users.findById(id).ifPresent(u -> refs.put(id, toRef(u))));
        return refs;
    }

    UserRef of(Long id) {
        return id == null ? null : users.findById(id).map(UserRefs::toRef).orElse(null);
    }

    private static UserRef toRef(User u) {
        return new UserRef(u.getLoginId(), u.getDisplayName());
    }
}

package io.choerodon.test.manager.app.service;

import java.util.List;
import java.util.Map;

import io.choerodon.devops.api.vo.AppServiceVersionRespVO;
import io.choerodon.devops.api.vo.ApplicationVersionRepDTO;

public interface DevopsService {

    void getTestStatus(Map<Long,List<String>> releaseName);

    List<Long>  getAppVersionId(String appName,Long projectId,Long appId);

    Map<Long, AppServiceVersionRespVO> getAppversion(Long projectId, List<Long> appVersionIds);

    void getPodStatus();
}

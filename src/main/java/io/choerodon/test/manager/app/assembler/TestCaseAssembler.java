package io.choerodon.test.manager.app.assembler;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.mybatis.entity.BaseDTO;
import io.choerodon.test.manager.api.vo.*;
import io.choerodon.test.manager.app.service.TestCaseLinkService;
import io.choerodon.test.manager.app.service.TestCycleCaseService;
import io.choerodon.test.manager.app.service.UserService;
import io.choerodon.test.manager.infra.dto.*;
import io.choerodon.test.manager.infra.mapper.*;
import org.apache.commons.lang.StringUtils;
import org.modelmapper.ModelMapper;
import org.modelmapper.TypeToken;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

/**
 * @author zhaotianxin
 * @since 2019/11/22
 */
@Component
public class TestCaseAssembler {
    private static final String BACKETNAME = "agile-service";

    @Autowired
    private TestProjectInfoMapper testProjectInfoMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private TestIssueFolderMapper testIssueFolderMapper;

    @Autowired
    private TestCaseMapper testCaseMapper;

    @Autowired
    private TestDataLogMapper testDataLogMapper;

    @Autowired
    private TestCaseLinkService testCaseLinkService;

    @Autowired
    private TestAttachmentMapper testAttachmentMapper;

    @Autowired
    private TestCycleCaseAttachmentRelMapper testCycleCaseAttachmentRelMapper;

    @Autowired
    private TestCycleCaseStepMapper testCycleCaseStepMapper;

    @Autowired
    private TestCycleCaseMapper testCycleCaseMapper;

    @Autowired
    private TestCycleCaseService testCycleCaseService;

    @Value("${services.attachment.url}")
    private String attachmentUrl;

    private static final String TYPE = "CYCLE_CASE";
    private ModelMapper modelMapper = new ModelMapper();

    @PostConstruct
    public void init() {
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
    }

    public TestCaseRepVO dtoToRepVo(TestCaseDTO testCaseDTO,Map<Long, TestIssueFolderDTO> folderMap) {
        Map<Long, UserMessageDTO> map = getUserMap(testCaseDTO, null);
        TestCaseRepVO testCaseRepVO = new TestCaseRepVO();
        modelMapper.map(testCaseDTO, testCaseRepVO);
        TestProjectInfoDTO testProjectInfoDTO = new TestProjectInfoDTO();
        testProjectInfoDTO.setProjectId(testCaseDTO.getProjectId());
        testCaseRepVO.setCaseNum(getIssueNum(testCaseDTO.getProjectId(),testCaseDTO.getCaseNum()));
        testCaseRepVO.setCreateUser(map.get(testCaseDTO.getCreatedBy()));
        testCaseRepVO.setLastUpdateUser(map.get(testCaseDTO.getLastUpdatedBy()));
        testCaseRepVO.setFolderName(folderMap.get(testCaseDTO.getFolderId()).getName());
        return testCaseRepVO;
    }

    public TestFolderCycleCaseVO setAssianUser(TestCycleCaseDTO testCycleCaseDTO){
        TestFolderCycleCaseVO testFolderCycleCaseVO = modelMapper.map(testCycleCaseDTO, TestFolderCycleCaseVO.class);
        Long assignedTo = testCycleCaseDTO.getAssignedTo();
        if(!assignedTo.equals(0)){
            BaseDTO baseDTO = new BaseDTO();
            baseDTO.setCreatedBy(assignedTo);
            Map<Long, UserMessageDTO> userMap = getUserMap(baseDTO, null);
            UserMessageDTO userMessageDTO = userMap.get(assignedTo);
            testFolderCycleCaseVO.setAssignedUser(userMessageDTO);
        }
        return testFolderCycleCaseVO;
    }

    public List<TestCaseRepVO> listDtoToRepVo(Long projectId,List<TestCaseDTO> list,Long planId){
        Map<Long, UserMessageDTO> userMap = getUserMap(null, modelMapper.map(list, new TypeToken<List<BaseDTO>>() {
        }.getType()));
        List<TestIssueFolderDTO> testIssueFolderDTOS = testIssueFolderMapper.selectListByProjectId(projectId);
        Map<Long, TestIssueFolderDTO> folderMap = testIssueFolderDTOS.stream().collect(Collectors.toMap(TestIssueFolderDTO::getFolderId, Function.identity()));
        List<Long> caseIds = null;
        if(!ObjectUtils.isEmpty(planId)){
            caseIds = testCycleCaseMapper.listByPlanId(planId);
        }
        List<Long> finalCaseIds = caseIds;
        List<TestCaseRepVO> collect = list.stream()
                .map(v -> {
                    TestCaseRepVO testCaseRepVO= dtoToRepVo(v,folderMap);
                    if(!CollectionUtils.isEmpty(finalCaseIds)){
                       if(finalCaseIds.contains(v.getCaseId())){
                           testCaseRepVO.setHasDisable(true);
                       }
                       else {
                           testCaseRepVO.setHasDisable(false);
                       }
                    }
                    return testCaseRepVO;
                }).collect(Collectors.toList());
        return collect;
    }


    public TestCaseInfoVO dtoToInfoVO(TestCaseDTO testCaseDTO){
        TestCaseInfoVO testCaseInfoVO = modelMapper.map(testCaseDTO, TestCaseInfoVO.class);
        // 获取用户信息
        Map<Long, UserMessageDTO> UserMessageDTOMap = getUserMap(testCaseDTO,null);
        if (!ObjectUtils.isEmpty(UserMessageDTOMap.get(testCaseDTO.getCreatedBy()))) {
            testCaseInfoVO.setCreateUser(UserMessageDTOMap.get(testCaseDTO.getCreatedBy()));
        }
        if (!ObjectUtils.isEmpty(UserMessageDTOMap.get(testCaseDTO.getCreatedBy()))) {
            testCaseInfoVO.setLastUpdateUser(UserMessageDTOMap.get(testCaseDTO.getLastUpdatedBy()));
        }
        // 用例的问题链接
        testCaseInfoVO.setIssuesInfos(testCaseLinkService.listIssueInfo(testCaseDTO.getProjectId(), testCaseDTO.getCaseId()));
        // 查询附件信息
        TestCaseAttachmentDTO testCaseAttachmentDTO = new TestCaseAttachmentDTO();
        testCaseAttachmentDTO.setCaseId(testCaseDTO.getCaseId());
        List<TestCaseAttachmentDTO> attachment = testAttachmentMapper.select(testCaseAttachmentDTO);
        if (!CollectionUtils.isEmpty(attachment)) {
            attachment.forEach(v -> {
                v.setUrl(attachmentUrl + "/" + BACKETNAME + "/" + v.getUrl());
            });
            testCaseInfoVO.setAttachment(attachment);
        }
        // 查询测试用例所属的文件夹
        TestIssueFolderDTO testIssueFolderDTO = testIssueFolderMapper.selectByPrimaryKey(testCaseDTO.getFolderId());
        if (!ObjectUtils.isEmpty(testIssueFolderDTO)) {
            testCaseInfoVO.setFolder(testIssueFolderDTO.getName());
        }
        testCaseInfoVO.setCaseNum(getIssueNum(testCaseDTO.getProjectId(),testCaseDTO.getCaseNum()));
        return testCaseInfoVO;
    }

    private String getIssueNum(Long projectId,String caseNum){
        TestProjectInfoDTO testProjectInfoDTO = new TestProjectInfoDTO();
        testProjectInfoDTO.setProjectId(projectId);
        TestProjectInfoDTO testProjectInfo = testProjectInfoMapper.selectOne(testProjectInfoDTO);
        String issue = String.format("%s-%s", testProjectInfo.getProjectCode(), caseNum);
        return issue;
    }

    public Map<Long, UserMessageDTO> getUserMap(BaseDTO baseDTO,List<BaseDTO> list){
        List<Long> userIds = new ArrayList<>();
        if (!ObjectUtils.isEmpty(baseDTO)){
            userIds.add(baseDTO.getCreatedBy());
            userIds.add(baseDTO.getLastUpdatedBy());
        }
        if (!CollectionUtils.isEmpty(list)) {
            list.forEach(v -> {
                userIds.add(v.getCreatedBy());
                userIds.add(v.getLastUpdatedBy());
            });
        }
        Map<Long, UserMessageDTO> userMessageDTOMap = userService.queryUsersMap(userIds);
        return  userMessageDTOMap;
    }

    public TestCycleCaseInfoVO cycleCaseExtraInfo(TestCycleCaseInfoVO testCycleCaseInfoVO) {
        if(!testCycleCaseInfoVO.getAssignedTo().equals(0)){
            BaseDTO baseDTO = new BaseDTO();
            baseDTO.setCreatedBy(testCycleCaseInfoVO.getAssignedTo());
            Map<Long, UserMessageDTO> UserMessageDTOMap = getUserMap(baseDTO, null);
            if (!ObjectUtils.isEmpty(UserMessageDTOMap.get(testCycleCaseInfoVO.getAssignedTo()))) {
                testCycleCaseInfoVO.setExecutor(UserMessageDTOMap.get(testCycleCaseInfoVO.getAssignedTo()));
            }
        }
        // 查询附件信息
        TestCycleCaseAttachmentRelDTO testCycleCaseAttachmentRelDTO = new TestCycleCaseAttachmentRelDTO();
        testCycleCaseAttachmentRelDTO.setAttachmentLinkId(testCycleCaseInfoVO.getExecuteId());
        testCycleCaseAttachmentRelDTO.setAttachmentType(TYPE);
        List<TestCycleCaseAttachmentRelDTO> testCycleCaseAttachmentRelDTOS = testCycleCaseAttachmentRelMapper.select(testCycleCaseAttachmentRelDTO);
        testCycleCaseInfoVO.setAttachment(modelMapper.map(testCycleCaseAttachmentRelDTOS, new TypeToken<List<TestCycleCaseAttachmentRelVO>>() {
        }.getType()));
        // 查询用例信息
        TestCaseDTO testCaseDTO = testCaseMapper.selectByPrimaryKey(testCycleCaseInfoVO.getCaseId());
        if(!ObjectUtils.isEmpty(testCaseDTO)){
            testCycleCaseInfoVO.setCaseNum(getIssueNum(testCaseDTO.getProjectId(),testCaseDTO.getCaseNum()));
            testCycleCaseInfoVO.setCaseFolderId(testCaseDTO.getFolderId());
        }
        return testCycleCaseInfoVO;
    }

    public TestCycleCaseUpdateVO dtoToUpdateVO(TestCycleCaseDTO testCycleCaseDTO) {
        if(ObjectUtils.isEmpty(testCycleCaseDTO)){
            throw new CommonException("error.cycle.case.null");
        }
        TestCycleCaseUpdateVO testCycleCaseUpdateVO = modelMapper.map(testCycleCaseDTO, TestCycleCaseUpdateVO.class);
        //查询步骤信息
        List<TestCycleCaseStepDTO> testCycleCaseStepDTOS = testCycleCaseStepMapper.selectByexecuteId(testCycleCaseDTO.getExecuteId());
        List<TestCycleCaseStepUpdateVO> testCycleCaseStepVOList = modelMapper.map(testCycleCaseStepDTOS, new TypeToken<List<TestCycleCaseStepUpdateVO>>() {
        }.getType());
        testCycleCaseUpdateVO.setTestCycleCaseStepUpdateVOS(testCycleCaseStepVOList);
        // 查询附件信息
        TestCycleCaseAttachmentRelDTO testCycleCaseAttachmentRelDTO = new TestCycleCaseAttachmentRelDTO();
        testCycleCaseAttachmentRelDTO.setAttachmentLinkId(testCycleCaseDTO.getExecuteId());
        List<TestCycleCaseAttachmentRelDTO> testCycleCaseAttachmentRelDTOS = testCycleCaseAttachmentRelMapper.select(testCycleCaseAttachmentRelDTO);
        testCycleCaseUpdateVO.setCycleCaseAttachmentRelVOList(modelMapper.map(testCycleCaseAttachmentRelDTOS, new TypeToken<List<TestCycleCaseAttachmentRelVO>>() {
        }.getType()));
        return testCycleCaseUpdateVO;
    }

    public TestCaseStepDTO cycleStepToCaseStep(TestCycleCaseStepDTO testCycleCaseStepDTO, TestCaseDTO testCaseDTO, CustomUserDetails userDetails){
        TestCaseStepDTO testCaseStepDTO = new TestCaseStepDTO();
        // todo rank未写
        testCaseStepDTO.setTestStep(testCycleCaseStepDTO.getTestStep());
        testCaseStepDTO.setTestData(testCycleCaseStepDTO.getTestData());
        testCaseStepDTO.setExpectedResult(testCycleCaseStepDTO.getExpectedResult());
        testCaseStepDTO.setIssueId(testCaseDTO.getCaseId());
        testCaseStepDTO.setLastUpdatedBy(userDetails.getUserId());
        testCaseStepDTO.setCreatedBy(userDetails.getUserId());
        testCaseStepDTO.setRank(StringUtils.abbreviate(UUID.randomUUID().toString(),8));
        return testCaseStepDTO;
    }

    // 执行同步
    public void AutoAsyncCase(List<TestCycleCaseDTO> testCycleCaseDTOS,Boolean changeCase,Boolean changeStep,Boolean changeAttach){
        testCycleCaseDTOS.forEach(v -> {
            CaseCompareRepVO caseCompareVO = new CaseCompareRepVO();
            caseCompareVO.setCaseId(v.getCaseId());
            caseCompareVO.setExecuteId(v.getExecuteId());
            caseCompareVO.setSyncToCase(false);
            caseCompareVO.setChangeStep(changeStep);
            caseCompareVO.setChangeCase(changeCase);
            caseCompareVO.setChangeAttach(changeAttach);
            testCycleCaseService.updateCompare(v.getProjectId(),caseCompareVO);
        });

    }
}
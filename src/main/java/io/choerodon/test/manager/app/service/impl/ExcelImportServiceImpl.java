package io.choerodon.test.manager.app.service.impl;

import static org.apache.poi.ss.usermodel.Cell.CELL_TYPE_STRING;

import java.util.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import feign.FeignException;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.choerodon.agile.api.vo.*;
import io.choerodon.agile.infra.common.enums.IssueTypeCode;
import io.choerodon.agile.infra.common.utils.AgileUtil;
import io.choerodon.base.domain.PageRequest;
import io.choerodon.core.exception.CommonException;
import io.choerodon.test.manager.api.vo.ExcelReadMeOptionVO;
import io.choerodon.test.manager.api.vo.TestFileLoadHistoryWithRateVO;
import io.choerodon.test.manager.app.service.*;
import io.choerodon.test.manager.infra.dto.*;
import io.choerodon.test.manager.infra.enums.TestAttachmentCode;
import io.choerodon.test.manager.infra.enums.TestFileLoadHistoryEnums;
import io.choerodon.test.manager.infra.feign.IssueFeignClient;
import io.choerodon.test.manager.infra.feign.TestCaseFeignClient;
import io.choerodon.test.manager.infra.mapper.TestFileLoadHistoryMapper;
import io.choerodon.test.manager.infra.mapper.TestIssueFolderMapper;
import io.choerodon.test.manager.infra.mapper.TestIssueFolderRelMapper;
import io.choerodon.test.manager.infra.util.ExcelUtil;
import io.choerodon.test.manager.infra.util.MultipartExcel;

@Service
public class ExcelImportServiceImpl implements ExcelImportService {

    private static final Logger logger = LoggerFactory.getLogger(ExcelImportServiceImpl.class);
    private static final String IMPORT_NOTIFY_CODE = "test-issue-import";
    private static final String HIDDEN_PRIORITY = "hidden_priority";
    private static final String HIDDEN_USER = "hidden_user";
    private static final String HIDDEN_COMPONENT = "hidden_component";
    private static final ExcelReadMeOptionVO[] README_OPTIONS = new ExcelReadMeOptionVO[9];
    private static final TestCaseStepDTO[] EXAMPLE_TEST_CASE_STEPS = new TestCaseStepDTO[3];
    private static final IssueCreateDTO[] EXAMPLE_ISSUES = new IssueCreateDTO[3];
    private static final String TYPE_CYCLE = "cycle";

    static {
        README_OPTIONS[0] = new ExcelReadMeOptionVO("用例概要", true);
        README_OPTIONS[1] = new ExcelReadMeOptionVO("用例描述", false);
        README_OPTIONS[2] = new ExcelReadMeOptionVO("优先级", false);
        README_OPTIONS[3] = new ExcelReadMeOptionVO("被指定人", false);
        README_OPTIONS[4] = new ExcelReadMeOptionVO("模块", false);
        README_OPTIONS[5] = new ExcelReadMeOptionVO("关联的issue", false);
        README_OPTIONS[6] = new ExcelReadMeOptionVO("测试步骤", false);
        README_OPTIONS[7] = new ExcelReadMeOptionVO("测试数据", false);
        README_OPTIONS[8] = new ExcelReadMeOptionVO("预期结果", false);

        for (int i = 0; i < EXAMPLE_TEST_CASE_STEPS.length; i++) {
            EXAMPLE_TEST_CASE_STEPS[i] = new TestCaseStepDTO();
            EXAMPLE_TEST_CASE_STEPS[i].setTestStep("步骤" + (i + 1));
            EXAMPLE_TEST_CASE_STEPS[i].setTestData("数据" + (i + 1));
            EXAMPLE_TEST_CASE_STEPS[i].setExpectedResult("结果" + (i + 1));
        }

        for (int i = 0; i < EXAMPLE_ISSUES.length; i++) {
            EXAMPLE_ISSUES[i] = new IssueCreateDTO();
            EXAMPLE_ISSUES[i].setSummary("概要" + (i + 1));
            EXAMPLE_ISSUES[i].setDescription("描述" + (i + 1));
        }
    }

    @Autowired
    private ExcelService excelService;

    @Autowired
    private UserService userService;

    @Autowired
    private TestCaseService testCaseService;

    @Autowired
    private TestCaseStepService testCaseStepService;

    @Autowired
    private FileService fileService;

    @Autowired
    private NotifyService notifyService;

    @Autowired
    private TestFileLoadHistoryMapper testFileLoadHistoryMapper;

    @Autowired
    private TestIssueFolderMapper testIssueFolderMapper;

    @Autowired
    private TestIssueFolderRelMapper testIssueFolderRelMapper;

    @Autowired
    private IssueFeignClient issueFeignClient;

    @Autowired
    private TestCaseFeignClient testCaseFeignClient;

    @Autowired
    private ModelMapper modelMapper;

    @Override
    public boolean cancelFileUpload(Long historyId) {
        return testFileLoadHistoryMapper.cancelFileUpload(historyId) == 1;
    }

    @Override
    public void downloadImportTemp(HttpServletRequest request, HttpServletResponse response, Long organizationId, Long projectId) {
        ExcelUtil.setExcelHeaderByStream(request, response);
        excelService.downloadWorkBookByStream(buildImportTemp(organizationId, projectId), response);
    }

    @Async
    @Override
    public void importIssueByExcel(Long organizationId, Long projectId, Long versionId, Long userId, Workbook issuesWorkbook) {
        TestIssueFolderDTO testIssueFolderDTO = getFolder(projectId, versionId, "导入");
        TestFileLoadHistoryDTO testFileLoadHistoryDTO = initLoadHistory(projectId, testIssueFolderDTO.getFolderId(), userId);
        TestFileLoadHistoryEnums.Status status = TestFileLoadHistoryEnums.Status.SUCCESS;
        List<Long> issueIds = new ArrayList<>();

        Sheet testCasesSheet = issuesWorkbook.getSheet("测试用例");

        if (isEmptyTemp(testCasesSheet)) {
            logger.info("空模板");
            finishImport(testFileLoadHistoryDTO, userId, status);
            return;
        }

        Iterator<Row> rowIterator = rowIteratorSkipFirst(testCasesSheet);

        double nonBlankRowCount = (testCasesSheet.getPhysicalNumberOfRows() - 1) / 95.;
        double progress = 0.;
        long successfulCount = 0L;
        long failedCount = 0L;
        List<Integer> errorRowIndexes = new ArrayList<>();
        IssueDTO issueDTO = null;
        Row currentRow;
        logger.info("开始导入");
        while (rowIterator.hasNext()) {
            currentRow = rowIterator.next();

            if (Objects.equals(TestFileLoadHistoryEnums.Status.valueOf(testFileLoadHistoryMapper
                    .queryLoadHistoryStatus(testFileLoadHistoryDTO.getId())), TestFileLoadHistoryEnums.Status.CANCEL)) {
                status = TestFileLoadHistoryEnums.Status.CANCEL;
                logger.info("已取消");
                removeRow(currentRow);
                if (!issueIds.isEmpty()) {
                    testCaseService.batchDeleteIssues(projectId, issueIds);
                }
                break;
            }

            if (isIssueHeaderRow(currentRow)) {
                issueDTO = processIssueHeaderRow(currentRow, organizationId, projectId, versionId, testIssueFolderDTO.getFolderId());
                if (issueDTO == null) {
                    failedCount++;
                } else {
                    successfulCount++;
                    issueIds.add(issueDTO.getIssueId());
                }
            }
            processRow(issueDTO, currentRow, errorRowIndexes);
            updateProgress(testFileLoadHistoryDTO, userId, ++progress / nonBlankRowCount);
        }

        testFileLoadHistoryDTO.setSuccessfulCount(successfulCount);
        testFileLoadHistoryDTO.setFailedCount(failedCount);

        if (!errorRowIndexes.isEmpty() && status != TestFileLoadHistoryEnums.Status.CANCEL) {
            logger.info("导入数据有误，上传 error workbook");
            shiftErrorRowsToTop(testCasesSheet, errorRowIndexes);
            status = checkoutStatus(uploadErrorWorkbook(issuesWorkbook, testFileLoadHistoryDTO), status);
        }

        finishImport(testFileLoadHistoryDTO, userId, status);
    }

    public Workbook buildImportTemp(Long organizationId, Long projectId) {
        Workbook importTemp = ExcelUtil.getWorkBook(ExcelUtil.Mode.XSSF);
        List<PriorityVO> priorityVOList = issueFeignClient.queryByOrganizationIdList(organizationId).getBody();
        List<UserDTO> userDTOS = userService.list(new PageRequest(1, 99999), projectId, null, null).getBody().getList();
        List<ComponentForListDTO> componentForListDTOS = testCaseService.listByProjectId(projectId).getList();

        List<String> priorityList = new ArrayList<>();
        for (PriorityVO priorityVO : priorityVOList) {
            if (priorityVO.getEnable()) {
                priorityList.add(priorityVO.getName());
            }
        }

        List<String> userNameList = new ArrayList<>();
        for (UserDTO userDTO : userDTOS) {
            userNameList.add(userDTO.getLoginName() + userDTO.getRealName());
        }

        List<String> componentList = new ArrayList<>();
        for (ComponentForListDTO componentForListDTO : componentForListDTOS) {
            componentList.add(componentForListDTO.getName());
        }
        addReadMeSheet(importTemp);
        addTestCaseSheet(importTemp, priorityList, userNameList, componentList);

        return importTemp;
    }

    private void addReadMeSheet(Workbook workbook) {
        Sheet readMeSheet = workbook.createSheet("README");
        workbook.setSheetOrder("README", 0);

        fillReadMeSheet(readMeSheet);
        setReadMeSheetStyle(readMeSheet);
    }

    private void addTestCaseSheet(Workbook workbook, List<String> priorityList, List<String> userNameList, List<String> componentList) {
        Sheet testCaseSheet = workbook.createSheet("测试用例");
        workbook.setSheetOrder("测试用例", 1);

        fillTestCaseSheet(testCaseSheet);
        setTestCaseSheetStyle(testCaseSheet);

        ExcelUtil.dropDownList2007(workbook, testCaseSheet, priorityList, 1, 500, 2, 2, HIDDEN_PRIORITY, 2);
        ExcelUtil.dropDownList2007(workbook, testCaseSheet, userNameList, 1, 500, 3, 3, HIDDEN_USER, 3);
        ExcelUtil.dropDownList2007(workbook, testCaseSheet, componentList, 1, 500, 4, 4, HIDDEN_COMPONENT, 4);
    }

    // 填充测试用例页内容
    private void fillTestCaseSheet(Sheet testCaseSheet) {
        writeHeader(testCaseSheet, 0, 0);
    }

    private void writeHeader(Sheet sheet, int rowNum, int colNum) {
        Row header = ExcelUtil.getOrCreateRow(sheet, rowNum);
        for (int i = 0; i < README_OPTIONS.length; i++) {
            Cell cell = header.createCell(i + colNum, CELL_TYPE_STRING);
            if (README_OPTIONS[i].getRequired()) {
                cell.setCellValue(README_OPTIONS[i].getFiled() + "*");
            } else {
                cell.setCellValue(README_OPTIONS[i].getFiled());
            }
        }
    }

    // 设置 README 页样式
    private void setReadMeSheetStyle(Sheet readMeSheet) {
        setSheetBaseStyle(readMeSheet);
    }

    // 设置通用的单元格样式
    private void setSheetBaseStyle(Sheet sheet) {
        CellStyle cellStyle = sheet.getWorkbook().createCellStyle();
        cellStyle.setAlignment(CellStyle.ALIGN_CENTER);
        cellStyle.setVerticalAlignment(CellStyle.VERTICAL_CENTER);
        sheet.setDefaultColumnWidth(16);
        for (Row row : sheet) {
            for (Cell cell : row) {
                cell.setCellStyle(cellStyle);
            }
        }
    }

    // 设置测试用例页样式
    private void setTestCaseSheetStyle(Sheet testCaseSheet) {
        setSheetBaseStyle(testCaseSheet);

        CellStyle cellStyle = testCaseSheet.getWorkbook().createCellStyle();
        cellStyle.setAlignment(CellStyle.ALIGN_CENTER);
        cellStyle.setVerticalAlignment(CellStyle.VERTICAL_CENTER);

        Font font = testCaseSheet.getWorkbook().createFont();
        font.setColor(Font.COLOR_RED);
        font.setBold(true);
        cellStyle.setFont(font);

        testCaseSheet.setDefaultColumnStyle(README_OPTIONS.length, cellStyle);
    }

    // 填充 README 页内容
    private void fillReadMeSheet(Sheet readMeSheet) {
        readMeSheet.createRow(0).createCell(0, CELL_TYPE_STRING).setCellValue("字段是否为必填项");
        readMeSheet.addMergedRegion(new CellRangeAddress(0, 0, 0, README_OPTIONS.length));

        readMeSheet.getRow(0).createCell(README_OPTIONS.length + 1).setCellValue("请至下一页，填写信息");
        readMeSheet.addMergedRegion(new CellRangeAddress(0, 7, README_OPTIONS.length + 1, README_OPTIONS.length + 3));

        readMeSheet.createRow(1).createCell(0, CELL_TYPE_STRING).setCellValue("是否必填/字段");
        readMeSheet.createRow(2).createCell(0, CELL_TYPE_STRING).setCellValue("必填项");
        readMeSheet.createRow(3).createCell(0, CELL_TYPE_STRING).setCellValue("选填项");

        int i = 0;
        while (i < README_OPTIONS.length) {
            ExcelReadMeOptionVO optionDTO = README_OPTIONS[i++];
            readMeSheet.getRow(1).createCell(i, CELL_TYPE_STRING).setCellValue(optionDTO.getFiled());
            readMeSheet.getRow(optionDTO.getRequired() ? 2 : 3).createCell(i, CELL_TYPE_STRING).setCellValue("√");
        }

        writeHeader(readMeSheet, 0, 0);

        readMeSheet.createRow(8).createCell(1, CELL_TYPE_STRING).setCellValue("示例");
        readMeSheet.addMergedRegion(new CellRangeAddress(8, 8, 1, 9));
        writeExample(readMeSheet, 9, 1, EXAMPLE_ISSUES[0], EXAMPLE_TEST_CASE_STEPS);
        writeExample(readMeSheet, 10, 1, EXAMPLE_ISSUES[1], EXAMPLE_TEST_CASE_STEPS[0]);
        writeExample(readMeSheet, 11, 1, EXAMPLE_ISSUES[2],
                EXAMPLE_TEST_CASE_STEPS[0],
                EXAMPLE_TEST_CASE_STEPS[1]
        );
    }

    private void writeExample(Sheet sheet, int rowNum, int colNum, IssueCreateDTO issueCreateDTO, TestCaseStepDTO... steps) {
        Row row = ExcelUtil.getOrCreateRow(sheet, rowNum);
        row.createCell(colNum, CELL_TYPE_STRING).setCellValue(issueCreateDTO.getSummary());
        row.createCell(colNum + 1, CELL_TYPE_STRING).setCellValue(issueCreateDTO.getDescription());
        row.createCell(colNum + 2, CELL_TYPE_STRING).setCellValue("高");
        row.createCell(colNum + 3, CELL_TYPE_STRING).setCellValue("1234张三");
        row.createCell(colNum + 4, CELL_TYPE_STRING).setCellValue("测试模块");
        row.createCell(colNum + 5, CELL_TYPE_STRING).setCellValue("XX-111");

        for (int i = 0; i < steps.length; i++) {
            row = ExcelUtil.getOrCreateRow(sheet, i + rowNum);
            row.createCell(colNum + 6, CELL_TYPE_STRING).setCellValue(steps[i].getTestStep());
            row.createCell(colNum + 7, CELL_TYPE_STRING).setCellValue(steps[i].getTestData());
            row.createCell(colNum + 8, CELL_TYPE_STRING).setCellValue(steps[i].getExpectedResult());
        }
    }

    @Transactional
    public TestIssueFolderDTO getFolder(Long projectId, Long versionId, String folderName) {
        TestIssueFolderDTO testIssueFolderDTO = new TestIssueFolderDTO();
        testIssueFolderDTO.setProjectId(projectId);
        testIssueFolderDTO.setVersionId(versionId);
        testIssueFolderDTO.setName(folderName);

        TestIssueFolderDTO targetTestIssueFolderDTO = testIssueFolderMapper.selectOne(testIssueFolderDTO);
        if (targetTestIssueFolderDTO == null) {
            targetTestIssueFolderDTO = new TestIssueFolderDTO();
            targetTestIssueFolderDTO.setType(TYPE_CYCLE);
            logger.info("{} 文件夹不存在，创建", folderName);

            if (targetTestIssueFolderDTO.getFolderId() != null) {
                throw new CommonException("error.issue.folder.insert.folderId.should.be.null");
            }
            testIssueFolderMapper.insert(testIssueFolderDTO);

            return testIssueFolderDTO;
        }

        logger.info("{} 文件夹已存在", folderName);
        return targetTestIssueFolderDTO;
    }

    private TestFileLoadHistoryDTO initLoadHistory(Long projectId, Long folderId, Long userId) {
        TestFileLoadHistoryDTO testFileLoadHistoryDTO = new TestFileLoadHistoryDTO();
        testFileLoadHistoryDTO.setProjectId(projectId);
        testFileLoadHistoryDTO.setActionType(TestFileLoadHistoryEnums.Action.UPLOAD_ISSUE.getTypeValue());
        testFileLoadHistoryDTO.setSourceType(TestFileLoadHistoryEnums.Source.FOLDER.getTypeValue());
        testFileLoadHistoryDTO.setLinkedId(folderId);
        testFileLoadHistoryDTO.setStatus(TestFileLoadHistoryEnums.Status.SUSPENDING.getTypeValue());
        testFileLoadHistoryDTO.setCreationDate(new Date());
        testFileLoadHistoryDTO.setSuccessfulCount(0L);
        testFileLoadHistoryDTO.setFailedCount(0L);
        testFileLoadHistoryDTO.setCreatedBy(userId);

        testFileLoadHistoryMapper.insert(testFileLoadHistoryDTO);

        return testFileLoadHistoryMapper.selectByPrimaryKey(testFileLoadHistoryDTO);
    }

    private boolean isEmptyTemp(Sheet sheet) {
        Iterator<Row> iterator = sheet.rowIterator();
        if (!iterator.hasNext()) {
            return true;
        }

        iterator.next();
        while (iterator.hasNext()) {
            if (!isEmptyRow(iterator.next())) {
                return false;
            }
        }
        return true;
    }

    private boolean isEmptyRow(Row row) {
        if (row == null) {
            return true;
        }

        for (int i = 0; i < README_OPTIONS.length; i++) {
            if (!ExcelUtil.isBlank(row.getCell(i))) {
                return false;
            }
        }

        return true;
    }

    private void finishImport(TestFileLoadHistoryDTO testFileLoadHistoryDTO, Long userId, TestFileLoadHistoryEnums.Status status) {
        testFileLoadHistoryDTO.setLastUpdateDate(new Date());
        testFileLoadHistoryDTO.setStatus(status.getTypeValue());
        testFileLoadHistoryMapper.updateByPrimaryKey(testFileLoadHistoryDTO);

        updateProgress(testFileLoadHistoryDTO, userId, 100.);
    }


    private Iterator<Row> rowIteratorSkipFirst(Sheet sheet) {
        Iterator<Row> rowIterator = sheet.rowIterator();
        if (rowIterator.hasNext()) {
            rowIterator.next();
        }

        return rowIterator;
    }

    private void removeRow(Row row) {
        for (int i = 0; i <= README_OPTIONS.length; i++) {
            if (row.getCell(i) != null) {
                row.removeCell(row.getCell(i));
            }
        }
    }

    private boolean isIssueHeaderRow(Row row) {
        if (row.getRowNum() == 0) {
            return false;
        }
        if (row.getRowNum() == 1) {
            return true;
        }

        String summary = ExcelUtil.getStringValue(row.getCell(0));
        String description = ExcelUtil.getStringValue(row.getCell(1));
        String priority = ExcelUtil.getStringValue(row.getCell(2));
        String user = ExcelUtil.getStringValue(row.getCell(3));
        String component = ExcelUtil.getStringValue(row.getCell(4));
        String issueLink = ExcelUtil.getStringValue(row.getCell(5));
        return StringUtils.isNotBlank(summary) || StringUtils.isNotBlank(description) || StringUtils.isNotBlank(priority)
                || StringUtils.isNotBlank(user) || StringUtils.isNotBlank(component) || StringUtils.isNotBlank(issueLink);
    }

    private IssueDTO processIssueHeaderRow(Row row, Long organizationId, Long projectId, Long versionId, Long folderId) {
        if (ExcelUtil.isBlank(row.getCell(0))) {
            markAsError(row, "测试概要不能为空");
            return null;
        }

        Long priorityId;
        if (ExcelUtil.isBlank(row.getCell(2))) {
            priorityId = AgileUtil.queryDefaultPriorityId(projectId, organizationId, issueFeignClient);
        } else {
            List<PriorityVO> priorityVOList = issueFeignClient.queryByOrganizationIdList(organizationId).getBody();
            Map<String, Long> priorityNameIdMap = new HashMap<>();
            for (PriorityVO priorityVO : priorityVOList) {
                if (priorityVO.getEnable()) {
                    priorityNameIdMap.put(priorityVO.getName(), priorityVO.getId());
                }
            }
            if (priorityNameIdMap.get(ExcelUtil.getStringValue(row.getCell(2))) == null) {
                markAsError(row, "优先级有误，请检查导入模板是否为最新数据。");
                return null;
            }
            priorityId = priorityNameIdMap.get(ExcelUtil.getStringValue(row.getCell(2)));
        }

        String description = ExcelUtil.getStringValue(row.getCell(1));
        String summary = ExcelUtil.getStringValue(row.getCell(0));

        IssueCreateDTO issueCreateDTO = new IssueCreateDTO();
        issueCreateDTO.setProjectId(projectId);
        issueCreateDTO.setPriorityCode("priority-" + priorityId);
        issueCreateDTO.setPriorityId(priorityId);
        issueCreateDTO.setSummary(summary);
        issueCreateDTO.setDescription(description);
        issueCreateDTO.setTypeCode(IssueTypeCode.ISSUE_TEST);
        issueCreateDTO.setIssueTypeId(AgileUtil.queryIssueTypeId(projectId, organizationId, IssueTypeCode.ISSUE_TEST, issueFeignClient));

        if (!ExcelUtil.isBlank(row.getCell(3))) {
            List<UserDTO> userDTOS = userService.list(new PageRequest(1, 99999), projectId, null, null).getBody().getList();
            Map<String, Long> userNameIDMap = new HashMap<>();
            for (UserDTO userDTO : userDTOS) {
                userNameIDMap.put(userDTO.getLoginName() + userDTO.getRealName(), userDTO.getId());
            }
            if (userNameIDMap.get(ExcelUtil.getStringValue(row.getCell(3))) == null) {
                markAsError(row, "指派人有误，请检查导入模板是否为最新数据。");
                return null;
            }
            issueCreateDTO.setAssigneeId(userNameIDMap.get(ExcelUtil.getStringValue(row.getCell(3))));
        }

        if (!ExcelUtil.isBlank(row.getCell(4))) {
            List<ComponentForListDTO> componentForListDTOS = testCaseService.listByProjectId(projectId).getList();
            Map<String, Long> componentNameIdMap = new HashMap<>();
            for (ComponentForListDTO componentForListDTO : componentForListDTOS) {
                componentNameIdMap.put(componentForListDTO.getName(), componentForListDTO.getComponentId());
            }
            if (componentNameIdMap.get(ExcelUtil.getStringValue(row.getCell(4))) == null) {
                markAsError(row, "模块有误，请检查导入模板是否为最新数据。");
                return null;
            }
            List<ComponentIssueRelVO> componentIssueRelVOList = new ArrayList<>();
            ComponentIssueRelVO componentIssueRelVO = new ComponentIssueRelVO();
            componentIssueRelVO.setComponentId(componentNameIdMap.get(ExcelUtil.getStringValue(row.getCell(4))));
            componentIssueRelVOList.add(componentIssueRelVO);
            issueCreateDTO.setComponentIssueRelVOList(componentIssueRelVOList);
        }


        if (!ExcelUtil.isBlank(row.getCell(5))) {
            String issueNumString = ExcelUtil.getStringValue(row.getCell(5));
            if (issueNumString.contains("-")) {
                issueNumString = issueNumString.split("-")[1];
            }
            IssueNumDTO issueNumDTO;

            try {
                issueNumDTO = testCaseFeignClient.queryIssueByIssueNum(projectId, issueNumString).getBody();
            } catch (FeignException e) {
                markAsError(row, "关联问题编号有误，仅支持关联故事、任务、缺陷类型，请检查录入的关联问题编号。");
                return null;
            }

            List<IssueLinkTypeDTO> issueLinkTypeDTOList = testCaseFeignClient.listIssueLinkType(projectId, null, new IssueLinkTypeSearchDTO()).getBody().getList();

            IssueLinkCreateDTO issueLinkCreateDTO = new IssueLinkCreateDTO();
            issueLinkCreateDTO.setIn(true);
            issueLinkCreateDTO.setLinkedIssueId(issueNumDTO.getIssueId());
            issueLinkCreateDTO.setLinkTypeId(issueLinkTypeDTOList.get(0).getLinkTypeId());
            List<IssueLinkCreateDTO> issueLinkCreateDTOList = new ArrayList<>();
            issueLinkCreateDTOList.add(issueLinkCreateDTO);

            issueCreateDTO.setIssueLinkCreateDTOList(issueLinkCreateDTOList);
        }


        VersionIssueRelVO versionIssueRelVO = new VersionIssueRelVO();
        versionIssueRelVO.setVersionId(versionId);
        versionIssueRelVO.setRelationType("fix");
        issueCreateDTO.setVersionIssueRelVOList(Lists.newArrayList(versionIssueRelVO));

        IssueDTO issueDTO = testCaseService.createTest(issueCreateDTO, projectId, "test");
        if (issueDTO != null) {
            TestIssueFolderRelDTO testIssueFolderRelDTO = new TestIssueFolderRelDTO();
            testIssueFolderRelDTO.setProjectId(projectId);
            testIssueFolderRelDTO.setVersionId(versionId);
            testIssueFolderRelDTO.setFolderId(folderId);
            testIssueFolderRelDTO.setIssueId(issueDTO.getIssueId());
            try {
                testIssueFolderRelMapper.insert(testIssueFolderRelDTO);
            } catch (Exception e) {
                markAsError(row, "导入测试任务异常");
                return null;
            }
        } else {
            markAsError(row, "导入测试任务异常");
        }
        return issueDTO;
    }

    private void processRow(IssueDTO issueDTO, Row row, List<Integer> errorRowIndexes) {
        if (issueDTO == null) {
            errorRowIndexes.add(row.getRowNum());
            return;
        }

        TestCaseStepProDTO testCaseStepProDTO = buildTestCaseStepDTO(issueDTO.getIssueId(), row);
        if (testCaseStepProDTO != null) {
            testCaseStepService.createOneStep(testCaseStepProDTO);
        }

        removeRow(row);
    }

    private void updateProgress(TestFileLoadHistoryDTO testFileLoadHistoryDTO, Long userId, double rate) {
        TestFileLoadHistoryWithRateVO testFileLoadHistoryWithRateVO = modelMapper
                .map(testFileLoadHistoryDTO, TestFileLoadHistoryWithRateVO.class);
        testFileLoadHistoryWithRateVO.setRate(rate);
        notifyService.postWebSocket(IMPORT_NOTIFY_CODE, userId.toString(), JSON.toJSONString(testFileLoadHistoryWithRateVO));

        logger.info("导入进度：{}", rate);
        if (rate == 100.) {
            logger.info("完成");
        }
    }

    private void shiftErrorRowsToTop(Sheet sheet, List<Integer> errorRowIndexes) {
        int i = 0;
        while (i < errorRowIndexes.size()) {
            shiftRow(sheet, errorRowIndexes.get(i), ++i);
        }
    }

    private String uploadErrorWorkbook(Workbook errorWorkbook, TestFileLoadHistoryDTO testFileLoadHistoryDTO) {
        ResponseEntity<String> response = fileService.uploadFile(TestAttachmentCode.ATTACHMENT_BUCKET, ".xlsx",
                new MultipartExcel("file", ".xlsx", errorWorkbook));

        boolean failed = false;
        if (response.getBody().startsWith("{")) {
            JSONObject jsonObject = JSON.parseObject(response.getBody());
            failed = jsonObject.containsKey("failed") && jsonObject.getBooleanValue("failed");
        }

        if (response.getStatusCode().is2xxSuccessful() && !failed) {
            testFileLoadHistoryDTO.setFileUrl(response.getBody());
            logger.debug(testFileLoadHistoryDTO.getFileUrl());
            return response.getBody();
        } else {
            testFileLoadHistoryDTO.setFileStream(ExcelUtil.getBytes(errorWorkbook));
            return null;
        }
    }

    private void markAsError(Row row, String errorMsg) {
        ExcelUtil.getOrCreateCell(row, README_OPTIONS.length, CELL_TYPE_STRING).setCellValue(errorMsg);

        logger.info("行 {} 发生错误：{}", row.getRowNum() + 1, errorMsg);
    }

    private TestCaseStepProDTO buildTestCaseStepDTO(Long issueId, Row row) {
        String testStep = ExcelUtil.getStringValue(row.getCell(6));
        String testData = ExcelUtil.getStringValue(row.getCell(7));
        String expectedResult = ExcelUtil.getStringValue(row.getCell(8));

        TestCaseStepProDTO testCaseStepDTO = null;
        if (StringUtils.isNotBlank(testStep) || StringUtils.isNotBlank(testData) || StringUtils.isNotBlank(expectedResult)) {
            testCaseStepDTO = new TestCaseStepProDTO();
            testCaseStepDTO.setTestStep(testStep);
            testCaseStepDTO.setTestData(testData);
            testCaseStepDTO.setExpectedResult(expectedResult);
            testCaseStepDTO.setIssueId(issueId);
        }

        return testCaseStepDTO;
    }

    private void shiftRow(Sheet sheet, int from, int to) {
        Row fromRow = sheet.getRow(from);
        Row toRow = ExcelUtil.getOrCreateRow(sheet, to);
        Cell fromCell;
        Cell toCell;
        for (int i = 0; i <= README_OPTIONS.length; i++) {
            fromCell = fromRow.getCell(i);
            if (fromCell != null) {
                fromRow.removeCell(fromCell);
                toCell = ExcelUtil.getOrCreateCell(toRow, i, CELL_TYPE_STRING);
                toCell.setCellValue(ExcelUtil.getStringValue(fromCell));
            }
        }
        toCell = toRow.getCell(README_OPTIONS.length);
        if (!ExcelUtil.isBlank(toCell)) {
            toCell.setCellStyle(sheet.getColumnStyle(README_OPTIONS.length));
        }
    }

    private TestFileLoadHistoryEnums.Status checkoutStatus(String uploadError, TestFileLoadHistoryEnums.Status status) {
        if (uploadError == null) {
            status = TestFileLoadHistoryEnums.Status.FAILURE;
        }
        return status;
    }
}

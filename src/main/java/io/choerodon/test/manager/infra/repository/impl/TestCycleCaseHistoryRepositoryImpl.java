//package io.choerodon.test.manager.infra.repository.impl;
//
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//import com.github.pagehelper.PageHelper;
//import com.github.pagehelper.PageInfo;
//
//import io.choerodon.core.convertor.ConvertHelper;
//import io.choerodon.core.convertor.ConvertPageHelper;
//import org.springframework.data.domain.Pageable;
//import io.choerodon.test.manager.domain.repository.TestCycleCaseHistoryRepository;
//import io.choerodon.test.manager.domain.test.manager.entity.TestCycleCaseHistoryE;
//import io.choerodon.test.manager.infra.util.DBValidateUtil;
//import io.choerodon.test.manager.infra.util.PageUtil;
//import io.choerodon.test.manager.infra.vo.TestCycleCaseAttachmentRelDTO;
//import io.choerodon.test.manager.infra.vo.TestCycleCaseHistoryDTO;
//import io.choerodon.test.manager.infra.mapper.TestCycleCaseHistoryMapper;
//
///**
// * Created by 842767365@qq.com on 6/11/18.
// */
//@Component
//public class TestCycleCaseHistoryRepositoryImpl implements TestCycleCaseHistoryRepository {
//    @Autowired
//    TestCycleCaseHistoryMapper testCycleCaseHistoryMapper;
//
//    @Override
//    public TestCycleCaseHistoryE insert(TestCycleCaseHistoryE testCycleCaseHistoryE) {
//        TestCycleCaseHistoryDTO convert = modeMapper.map(testCycleCaseHistoryE, TestCycleCaseHistoryDTO.class);
//        DBValidateUtil.executeAndvalidateUpdateNum(testCycleCaseHistoryMapper::insert, convert, 1, "error.history.insert");
//        return modeMapper.map(convert, TestCycleCaseHistoryE.class);
//    }
//
//    @Override
//    public PageInfo<TestCycleCaseHistoryE> query(TestCycleCaseHistoryE testCycleCaseHistoryE, Pageable pageable) {
//        TestCycleCaseHistoryDTO convert = modeMapper.map(testCycleCaseHistoryE, TestCycleCaseHistoryDTO.class);
//
//        PageInfo<TestCycleCaseAttachmentRelDTO> serviceDOPage = PageHelper.startPage(pageable.getPageNumber(),
//                pageable.getPageSize(), PageUtil.sortToSql(pageable.getSort())).doSelectPageInfo(() -> testCycleCaseHistoryMapper.query(convert));
//
//        return ConvertPageHelper.convertPageInfo(serviceDOPage, TestCycleCaseHistoryE.class);
//    }
//}

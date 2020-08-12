import React, {
  useEffect, useContext, useState, useRef, useReducer,
} from 'react';
import {
  Icon, Card, Spin, Tooltip,
} from 'choerodon-ui';
import {
  Page, Header, Content, Breadcrumb,
} from '@choerodon/boot';
import { Choerodon } from '@choerodon/boot';
import { observer } from 'mobx-react-lite';
import { withRouter } from 'react-router-dom';
import { FormattedMessage } from 'react-intl';
import { toJS } from 'mobx';
import _ from 'lodash';
import { Modal, Button, message } from 'choerodon-ui/pro';
import queryString from 'query-string';
import { uploadFile, deleteFile } from '@/api/FileApi';
import { StatusTags, RichTextShow } from '../../../../components';
import {
  executeDetailLink, returnBeforeTextUpload, delta2Html, text2Delta,
} from '../../../../common/utils';
import { updateDetail, updateSidebarDetail } from '../../../../api/ExecuteDetailApi';
import './TestPlanExecuteDetail.less';
import {
  ExecuteDetailSide, CreateBug, StepTable, QuickOperate, ExecuteHistoryTable,
} from './components';
import Store from './stores';
import EditExecuteIssue from './components/EditExecuteIssue';
import StepTableDataSet from './stores/StepTableDataSet';

const CardWrapper = ({
  children, title, style, titleClassName,
}) => (
  <Card
    title={null}
    style={style}
    bodyStyle={{ paddingBottom: 0, paddingTop: 20 }}
    bordered={false}
  >
    <span className={`c7n-test-execute-detail-card-title ${titleClassName || ''}`}>{title}</span>
    {children}
  </Card>
);
function TestPlanExecuteDetail(props) {
  const context = useContext(Store);
  const {
    ExecuteDetailStore, stepTableDataSet, executeHistoryDataSet, testStatusDataSet,
  } = context;
  const [syncLoading, setSyncLoading] = useState(false);
  const [preconditionState, setPreconditionState] = useReducer((state, action) => {
    switch (action.type) {
      case 'init':
        return ({
          loaded: true,
          iconVisible: action.desHeight > 52,
          desVisible: !(action.desHeight > 52),
          desHeight: action.desHeight,
        });
      case 'uHeight':
        return ({
          ...state,
          iconVisible: action.desHeight > 52,
          desHeight: action.desHeight,
        });
      case 'visible':
        return ({
          ...state,
          iconVisible: action.desHeight ? action.desHeight > 52 : state.iconVisible,
          desVisible: !state.desVisible,
        });
      case 'destroy':
        return ({
          loaded: false,
          iconVisible: false,
          desVisible: true,
          desHeight: 0,
        });
      default:
        return state;
    }
  }, {
    loaded: false,
    iconVisible: false,
    desVisible: true,
    desHeight: 0,
  });
  const preconditionRef = useRef(null);
  useEffect(() => {
    const { executeId } = context;
    ExecuteDetailStore.setDetailParams(queryString.parse(context.location.search.replace(/%253D/g, '%3D'))); // 全局替换 id加密后 防止有% 被转义
    ExecuteDetailStore.getInfo(executeId);
    ExecuteDetailStore.setId(executeId);
  }, [ExecuteDetailStore, context, context.match.params]);


  const goExecute = (mode) => {
    const detailData = ExecuteDetailStore.getDetailData;
    const { nextExecuteId, previousExecuteId } = detailData;
    const { history } = context;
    const toExecuteId = mode === 'pre' ? previousExecuteId : nextExecuteId;
    if (toExecuteId) {
      const {
        contents, plan_id: planId, cycle_id: cycleId, assignerId, executionStatus, summary,
      } = queryString.parse(context.location.search.replace(/%253D/g, '%3D'));
      const filters = {
        cycle_id: cycleId,
        plan_id: planId,
        assignerId,
        contents,
        executionStatus,
        summary,
      };
      history.replace(executeDetailLink(toExecuteId, filters));
      // ExecuteDetailStore.getInfo(toExecuteId);
    }
  };

  const handleToggleExecuteDetailSide = () => {
    const visible = ExecuteDetailStore.ExecuteDetailSideVisible;
    ExecuteDetailStore.setExecuteDetailSideVisible(!visible);
  };

  const handleSubmit = (updateData) => {
    const detailData = ExecuteDetailStore.getDetailData;
    const newData = { ...detailData, ...updateData };
    updateDetail(newData).then(() => {
      ExecuteDetailStore.getInfo();
      executeHistoryDataSet.query();
    }).catch(() => {
      Choerodon.prompt('网络异常');
    });
  };


  const quickPassOrFail = (text) => {
    const detailData = { ...ExecuteDetailStore.getDetailData };
    const { statusList } = ExecuteDetailStore;
    if (_.find(statusList, { projectId: 0, statusName: text })) {
      const statusItem = _.find(statusList, { projectId: 0, statusName: text }) || {};
      detailData.executionStatus = statusItem.statusId;
      detailData.executionStatusName = statusItem.statusName;
      updateDetail(detailData).then(() => {
        ExecuteDetailStore.getInfo();
        executeHistoryDataSet.query();
      }).catch((error) => {
        Choerodon.prompt(`${error || '网络错误'}`);
      });
    } else {
      Choerodon.prompt('未找到对应状态');
    }
  };

  const quickHandle = (statusName) => {
    quickPassOrFail(statusName);
  };

  const handleHiddenCreateBug = () => {
    ExecuteDetailStore.setCreateBugShow(false);
  };

  const handleBugCreate = () => {
    ExecuteDetailStore.setCreateBugShow(false);
    ExecuteDetailStore.getInfo();
    stepTableDataSet.query();
  };


  /**
   * 批量删除已上传文件（修改用例 保存）
   * 
   * @param {*} files 
   */
  async function deleteFiles(files = []) {
    files.forEach((file) => {
      deleteFile(file);
    });
    return true;
  }
  /**
   * 更新执行用例数据
   * @param {*} data 
   */
  function UpdateExecuteData(data) {
    const { executeId } = data;
    const testCycleCaseStepUpdateVOS = data.testCycleCaseStepUpdateVOS.map(
      (i) => {
        let { stepId } = i;
        let { executeStepId } = i;
        if (String(i.stepId).indexOf('.') !== -1) {
          stepId = 0;
          executeStepId = null;
        }
        return {
          ...i,
          stepId,
          executeId,
          executeStepId,
        };
      },
    );
    return new Promise((resolve) => {
      returnBeforeTextUpload(data.description, data, async (res) => {
        const newData = {
          ...res,
          fileList: [],
          caseStepVOS: [],
          testCycleCaseStepUpdateVOS,
        };
        const { isAsync = false } = newData;
        const { fileList } = res;
        if (fileList) {
          const formDataAdd = new FormData();
          const formDataDel = [];
          fileList.forEach((file) => {
            if (!file.status) {
              formDataAdd.append('file', file);
            } else if (file.status && file.status === 'removed') {
              formDataDel.push(file);
            }
          });

          const config = {
            attachmentLinkId: res.executeId, attachmentType: 'CYCLE_CASE',
          };
          if (formDataAdd.has('file')) {
            await uploadFile(formDataAdd, config);
          }
          // 删除文件 只能单个文件删除， 进行遍历删除
          await deleteFiles(formDataDel.map(i => i.id));
        }
        await updateSidebarDetail(newData);
        message.success(`${isAsync ? '同步修改成功' : '修改成功'}`);
        resolve(true);
      });
    });
  }

  /**
   * 保存同步用例
   */
  const handleSaveSyncCase = async (modal) => {
    const { editExecuteCaseDataSet } = context;
    if (editExecuteCaseDataSet.current && editExecuteCaseDataSet.validate()) {
      // 进行提交数据
      setSyncLoading(true);
      const newData = {
        ...editExecuteCaseDataSet.current.toData(),
        isAsync: true,
      };
      if (editExecuteCaseDataSet.current.status !== 'sync') {
        if (!await UpdateExecuteData(newData)) {
          message.info('同步修改失败');
        }
      } else {
        message.info('未做任何修改');
      }
    }
    setSyncLoading(false);
    modal.close();
  };
  /**
   * 取消时数据清空
   */
  const handleCloseEdit = async () => {
    const { editExecuteCaseDataSet } = context;
    if (editExecuteCaseDataSet.current) {
      editExecuteCaseDataSet.splice(0, 1);
    }
    return true;
  };
  /**
   * 关闭回调，数据有更新则刷新页面
   * 无论数据是否有变化都删除数据
   */
  const onRefreshAfterClose = () => {
    const { editExecuteCaseDataSet } = context;

    if (editExecuteCaseDataSet.current) {
      if (editExecuteCaseDataSet.current.status === 'update') {
        ExecuteDetailStore.getInfo();
        stepTableDataSet.query();
        executeHistoryDataSet.query();
      }
      editExecuteCaseDataSet.splice(0, 1);
    }
  };
  const handleOpenEdit = () => {
    const { editExecuteCaseDataSet, executeId } = context;
    const detailData = ExecuteDetailStore.getDetailData;
    const editModal = Modal.open({
      key: 'editExecuteIssue',
      title: '修改用例',
      drawer: true,
      style: {
        width: 740,
      },
      afterClose: onRefreshAfterClose,
      onCancel: handleCloseEdit,
      children: (
        <EditExecuteIssue
          editDataset={editExecuteCaseDataSet}
          executeId={executeId}
          UpdateExecuteData={UpdateExecuteData}
        />
      ),
      footer: (okBtn, cancelBtn) => (
        <div>
          {okBtn}
          {detailData.caseHasExist ? <Button loading={syncLoading} funcType="raised" color="primary" onClick={handleSaveSyncCase.bind(this, editModal)}>保存并同步到用例库</Button>
            : (
              <Tooltip title="相关用例已删除">
                <Button funcType="raised" color="primary">保存并同步到用例库</Button>
              </Tooltip>
            )
          }
          {cancelBtn}
        </div>
      ),
      okText: '保存',
    });
  };
  useEffect(() => {
    if (preconditionRef.current) {
      setPreconditionState({ type: 'init', desHeight: preconditionRef.current.offsetHeight });
    }
  }, [preconditionRef]);

  // 默认只显示15个字其余用... 进行省略
  const renderBreadcrumbTitle = (text) => {
    const ellipsis = '...';
    const textArr = [...text];
    return textArr.length > 15 ? <Tooltip title={text}>{`${textArr.slice(0, 15).join('') + ellipsis}`}</Tooltip> : text;
  };
  function renderRichText(text, isEllipsis = false) {
    const textArr = [{ insert: '前置条件：' }];
    if (text && text !== '') {
      const tempText = text2Delta(text);
      if (Array.isArray(tempText)) {
        textArr.push(...tempText);
      } else {
        textArr.push({ insert: tempText });
      }
    }
    return <div className={`c7n-test-execute-detail-card-title-description-head-content${isEllipsis ? '-ellipsis' : ''}`}><RichTextShow data={delta2Html(JSON.stringify(textArr))} /></div>;
  }

  function render() {
    // disabled 用于禁止action列
    const { disabled } = props;
    const { loading } = ExecuteDetailStore;
    const detailData = ExecuteDetailStore.getDetailData;
    const visible = ExecuteDetailStore.ExecuteDetailSideVisible;
    const statusList = ExecuteDetailStore.getStatusList;
    const createBugShow = ExecuteDetailStore.getCreateBugShow;
    const defectType = ExecuteDetailStore.getDefectType;
    const createDefectTypeId = ExecuteDetailStore.getCreateDefectTypeId;
    const { statusColor, statusName } = ExecuteDetailStore.getStatusById(detailData.executionStatus);
    const {
      summary, nextExecuteId, previousExecuteId, planStatus = 'done',
    } = detailData;
    return (
      <Page
        className="c7n-test-execute-detail"
        service={[
          'choerodon.code.project.test.test-plan.ps.default',
        ]}
      >
        <Header
          title={<FormattedMessage id="execute_detail" />}
        // backPath={disabled ? TestPlanLink() : TestExecuteLink()}
        >
          <Button icon="find_in_page" funcType="flat" type="primary" onClick={handleToggleExecuteDetailSide}>
            {/* <Icon type={visible ? 'format_indent_decrease' : 'format_indent_increase'} /> */}
            {visible ? '隐藏详情' : '查看详情'}
          </Button>


          {planStatus !== 'done'
            && <Button icon="mode_edit" funcType="flat" type="primary" onClick={handleOpenEdit}>修改用例</Button>
          }
          <Button
            disabled={!previousExecuteId}
            onClick={() => {
              goExecute('pre');
            }}
          >
            <Icon type="navigate_before" />
            <span><FormattedMessage id="execute_pre" /></span>
          </Button>
          <Button
            disabled={!nextExecuteId}
            onClick={() => {
              goExecute('next');
            }}
          >
            <span><FormattedMessage id="execute_next" /></span>
            <Icon type="navigate_next" />
          </Button>

        </Header>

        <Breadcrumb title={detailData ? renderBreadcrumbTitle(summary) : null} />
        <Content style={{ padding: visible ? '0 437px 0 0' : 0 }}>

          <Spin spinning={false} style={{ display: 'flex' }}>
            <div style={{ display: 'flex', width: '100%', height: '100%' }}>
              {/* 左边内容区域 */}
              <div
                style={{
                  flex: 1,
                  overflow: 'hidden',
                }}
              >
                <div className="c7n-test-execute-detail-header">
                  {detailData && (
                    <div style={{ display: 'flex', alignItems: 'center' }}>
                      <span style={{ fontSize: '20px' }}>{renderBreadcrumbTitle(summary)}</span>
                      <StatusTags
                        style={{ height: 20, lineHeight: '20px', marginLeft: 10 }}
                        color={statusColor}
                        name={statusName}
                      />
                    </div>
                  )}
                  {planStatus === 'doing'
                    && (
                      <QuickOperate
                        readOnly={planStatus !== 'doing'}
                        statusList={statusList}
                        quickHandle={quickHandle}
                        onSubmit={handleSubmit}
                      />
                    )}
                </div>

                <CardWrapper
                  title={(
                    <div className="c7n-test-execute-detail-card-title-description">
                      <div className="c7n-test-execute-detail-card-title-description-head" ref={preconditionRef}>
                        {/* <span className="c7n-test-execute-detail-card-title-description-head-label">
                          前置条件
                        </span> */}
                        {renderRichText(detailData.description, preconditionState.iconVisible && !preconditionState.desVisible)}
                        <span className="c7n-test-execute-detail-card-title-description-head-more">
                          <Icon style={{ cursor: 'pointer ' }} type={`expand_${preconditionState.desVisible ? 'less' : 'more'}`} onClick={() => { setPreconditionState({ type: 'visible', desHeight: preconditionRef.current.offsetHeight }); }} />
                        </span>
                      </div>
                      {[
                        <FormattedMessage id="execute_testDetail" />,
                        <span style={{ marginLeft: 5 }}>{`（${stepTableDataSet.totalCount}）`}</span>,
                      ]}
                    </div>
                  )}
                // titleClassName="c7n-test-execute-detail-card-title-description"
                >
                  <StepTable
                    dataSet={stepTableDataSet}
                    updateHistory={() => executeHistoryDataSet.query()} // 更新执行历史
                    testStatusDataSet={testStatusDataSet}
                    readOnly={planStatus === 'done'} // 数据是否只读
                    operateStatus={planStatus === 'doing'} // 数据是否可以进行状态更改/缺陷更改
                    ExecuteDetailStore={ExecuteDetailStore}
                  />
                </CardWrapper>

                <CardWrapper title={<FormattedMessage id="execute_executeHistory" />}>
                  <ExecuteHistoryTable
                    dataSet={executeHistoryDataSet}
                  />
                </CardWrapper>
              </div>
              {/* 右侧侧边栏 */}
              {visible && (
                <ExecuteDetailSide
                  disabled={disabled}
                  detailData={detailData}
                  fileList={detailData.attachment}
                  status={{ statusColor, statusName }}
                  onClose={handleToggleExecuteDetailSide}
                />
              )}
              {
                createBugShow && (
                  <CreateBug
                    visible={createBugShow}
                    defectType={defectType}
                    id={createDefectTypeId}
                    onCancel={handleHiddenCreateBug}
                    onOk={handleBugCreate}
                  />
                )
              }
            </div>
          </Spin>
        </Content>

      </Page>
    );
  }
  return render();
}


export default withRouter(observer(TestPlanExecuteDetail));

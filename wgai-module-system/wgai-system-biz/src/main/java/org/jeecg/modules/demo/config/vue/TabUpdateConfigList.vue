<template>
  <a-card :bordered="false">
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @submit.prevent="searchQuery">
        <a-row :gutter="24">
          <a-col :md="8" :sm="24">
            <a-form-item label="配置名称">
              <a-input v-model="queryParam.configName" placeholder="请输入配置名称" allow-clear @pressEnter="searchQuery" />
            </a-form-item>
          </a-col>
          <a-col :md="8" :sm="24">
            <a-form-item label="启用状态">
              <a-select v-model="queryParam.enabled" placeholder="全部" allow-clear>
                <a-select-option :value="1">启用</a-select-option>
                <a-select-option :value="0">停用</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :md="8" :sm="24">
            <a-button type="primary" icon="search" @click="searchQuery">查询</a-button>
            <a-button style="margin-left: 8px" icon="reload" @click="searchReset">重置</a-button>
          </a-col>
        </a-row>
      </a-form>
    </div>
    <div class="table-operator">
      <a-button type="primary" icon="plus" @click="handleAdd">新增配置</a-button>
      <a-button icon="reload" @click="loadData()">刷新</a-button>
      <a-button icon="download" @click="handleExportXls('更新上传配置')">导出</a-button>
      <a-upload name="file" :show-upload-list="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl" @change="handleImportExcel">
        <a-button icon="import">导入</a-button>
      </a-upload>
      <a-button v-if="selectedRowKeys.length" type="danger" icon="delete" @click="batchDel">批量删除</a-button>
    </div>
    <a-alert v-if="selectedRowKeys.length" type="info" show-icon style="margin-bottom: 16px">
      <span slot="message">已选择 {{ selectedRowKeys.length }} 项 <a @click="onClearSelected">清空</a></span>
    </a-alert>
    <a-table ref="table" size="middle" bordered row-key="id"
      :columns="columns" :data-source="dataSource" :pagination="ipagination" :loading="loading"
      :scroll="{ x: 1800 }"
      :row-selection="{ selectedRowKeys: selectedRowKeys, onChange: onSelectChange, getCheckboxProps: record => ({ props: { disabled: record.updateStatus === 'RUNNING' } }) }"
      @change="handleTableChange">
      <template slot="scope" slot-scope="text, record">
        <a-tag v-if="isEnabled(record.frontendEnabled)" color="blue">前端</a-tag>
        <a-tag v-if="isEnabled(record.backendEnabled)" color="purple">后端 lib</a-tag>
        <a-tag v-if="isEnabled(record.dbSyncEnabled)" color="cyan">数据库结构</a-tag>
        <span v-if="!hasScope(record)">未配置</span>
      </template>
      <template slot="enabled" slot-scope="text">
        <a-tag :color="isEnabled(text) ? 'green' : 'default'">{{ isEnabled(text) ? '启用' : '停用' }}</a-tag>
      </template>
      <template slot="status" slot-scope="text">
        <a-tag :color="statusInfo(text).color">{{ statusInfo(text).label }}</a-tag>
      </template>
      <template slot="progress" slot-scope="text, record">
        <a-progress v-if="progressMap[String(record.id)]" size="small" :percent="Number(progressMap[String(record.id)].percent) || 0" :status="record.updateStatus === 'FAILED' ? 'exception' : record.updateStatus === 'SUCCESS' ? 'success' : 'active'" />
        <a @click="showUpdateProgress(record)">查看进度 / 文件</a>
      </template>
      <template slot="result" slot-scope="text">
        <a-tooltip :title="text"><div class="result-text">{{ text || '—' }}</div></a-tooltip>
      </template>
      <template slot="action" slot-scope="text, record">
        <a-button type="link" size="small" :disabled="record.updateStatus === 'RUNNING'" @click="handleEdit(record)">编辑</a-button>
        <a-divider type="vertical" />
        <a @click="handleDetail(record)">详情</a>
        <a-divider type="vertical" />
        <a-button type="link" size="small" :disabled="!canSync(record)" @click="handleSyncUpdate(record)">同步更新</a-button>
        <a-button v-if="record.updateStatus === 'FAILED'" type="link" size="small" :disabled="!canSync(record) || (progressMap[String(record.id)] && progressMap[String(record.id)].retryAllowed === false)" @click="handleSyncUpdate(record, true)">失败重试</a-button>
        <a-divider type="vertical" />
        <a-popconfirm v-if="record.updateStatus !== 'RUNNING'" title="清空成功任务暂存并保留最近一份完整备份；当前失败重试缓存及恢复失败目录会保留。" @confirm="cleanupUpdateFiles(record)">
          <a>清理残留</a>
        </a-popconfirm>
        <a-divider type="vertical" />
        <a-popconfirm v-if="record.updateStatus !== 'RUNNING'" title="确定删除这条更新配置吗？" @confirm="handleDelete(record.id)">
          <a class="delete-link">删除</a>
        </a-popconfirm>
      </template>
    </a-table>
    <a-modal title="平台更新进度" :visible="progressVisible" :width="780" :footer="null" @cancel="progressVisible = false">
      <update-progress-panel v-if="progressVisible" :config-id="progressId" :info="selectedProgress" :connection-error="progressErrors[String(progressId)] || ''" @retry="retryProgressTask" />
    </a-modal>
    <tab-update-config-modal ref="modalForm" @ok="modalFormOk" />
    <a-modal :title="syncRetry ? '失败任务重试确认' : '同步更新配置确认'" :visible="syncVisible" :width="720" @cancel="syncVisible = false">
      <a-alert type="info" show-icon message="执行后将按配置下载、备份并同步更新"
        description="后端更新会停止并重启服务，期间页面可能暂时断开。数据库DDL不能随文件自动回滚，执行前请确认数据备份。" style="margin-bottom: 16px" />
      <a-alert v-if="syncRetry" type="info" show-icon message="优先复用失败任务中已完成且校验通过的文件；本地未变的比对结果可复用。数据库重新比对和去重，不重复执行旧计划。" style="margin-bottom: 12px" />
      <a-descriptions v-if="syncRecord" bordered :column="1" size="small">
        <a-descriptions-item label="配置名称">{{ syncRecord.configName }}</a-descriptions-item>
        <a-descriptions-item label="更新器版本">{{ syncUpdaterVersion }}</a-descriptions-item>
        <a-descriptions-item label="生效下载白名单">{{ syncExecution.allowedHosts || '使用后端配置' }}</a-descriptions-item>
        <a-descriptions-item label="前端保留目录">{{ syncExecution.frontendPreservePaths || '使用后端配置' }}</a-descriptions-item>
        <a-descriptions-item label="更新器 Java">{{ syncExecution.javaExecutable || '沿用当前 Java' }}</a-descriptions-item>
        <a-descriptions-item label="当前版本">{{ syncRecord.currentVersion || '—' }}</a-descriptions-item>
        <template v-if="isEnabled(syncRecord.frontendEnabled)">
          <a-descriptions-item label="前端部署目录"><span class="path-text">{{ syncRecord.frontendDir || '未配置' }}</span></a-descriptions-item>
          <a-descriptions-item label="前端更新资源"><span class="path-text">{{ syncRecord.frontendDownloadUrl || '使用版本清单中的文件列表或地址' }}</span></a-descriptions-item>
        </template>
        <template v-if="isEnabled(syncRecord.backendEnabled)">
          <a-descriptions-item label="停止脚本"><span class="path-text">{{ syncExecution.stopScript || syncRecord.stopScript || '未配置' }}</span></a-descriptions-item>
          <a-descriptions-item label="启动脚本"><span class="path-text">{{ syncExecution.startScript || syncRecord.startScript || '未配置' }}</span></a-descriptions-item>
          <a-descriptions-item label="健康检查地址"><span class="path-text">{{ syncExecution.healthUrl || syncRecord.healthUrl || '未配置' }}</span></a-descriptions-item>
          <a-descriptions-item label="后端 lib 目录"><span class="path-text">{{ syncRecord.backendLibDir || '未配置' }}</span></a-descriptions-item>
          <a-descriptions-item label="后端更新资源"><span class="path-text">{{ syncRecord.backendDownloadUrl || '使用版本清单中的文件列表或地址' }}</span></a-descriptions-item>
        </template>
        <a-descriptions-item v-if="isEnabled(syncRecord.dbSyncEnabled)" label="数据库结构文件"><span class="path-text">{{ syncRecord.dbSchemaUrl || '未配置' }}</span></a-descriptions-item>
        <a-descriptions-item label="备份目录"><span class="path-text">{{ syncRecord.backupDir || '未配置' }}</span></a-descriptions-item>
      </a-descriptions>
      <template slot="footer"><a-button :disabled="syncSubmitting" @click="syncVisible = false">取消</a-button><a-button type="primary" :loading="syncSubmitting" @click="submitSyncUpdate">确认同步更新</a-button></template>
    </a-modal>
  </a-card>
</template>
<script>
import '@/assets/less/TableExpand.less'
import { getAction, postAction } from '@/api/manage'
import { mixinDevice } from '@/utils/mixin'
import { JeecgListMixin } from '@/mixins/JeecgListMixin'
import TabUpdateConfigModal from './modules/TabUpdateConfigModal'
import UpdateProgressPanel from './modules/UpdateProgressPanel'
import { UpdateProgressMixin } from './modules/UpdateProgressMixin'

export default {
  name: 'TabUpdateConfigList',
  mixins: [JeecgListMixin, mixinDevice, UpdateProgressMixin],
  components: { TabUpdateConfigModal, UpdateProgressPanel },
  data () {
    return {
      description: '更新配置管理',
      syncVisible: false,
      syncRetry: false,
      syncSubmitting: false,
      syncRecord: null,
      syncExecution: {},
      syncUpdaterVersion: '',
      columns: [
        { title: '配置名称', dataIndex: 'configName', width: 180 },
        { title: '更新范围', key: 'scope', width: 240, scopedSlots: { customRender: 'scope' } },
        { title: '当前版本', dataIndex: 'currentVersion', width: 120 },
        { title: '启用状态', dataIndex: 'enabled', width: 100, scopedSlots: { customRender: 'enabled' } },
        { title: '更新状态', dataIndex: 'updateStatus', width: 110, scopedSlots: { customRender: 'status' } },
        { title: '结束时间', dataIndex: 'lastUpdateTime', width: 190 },
        { title: '更新进度', key: 'progress', width: 200, scopedSlots: { customRender: 'progress' } },
        { title: '最近更新结果', dataIndex: 'lastUpdateMessage', width: 240, scopedSlots: { customRender: 'result' } },
        { title: '备注', dataIndex: 'remark', width: 160, ellipsis: true },
        { title: '操作', dataIndex: 'action', width: 450, fixed: 'right', align: 'center', scopedSlots: { customRender: 'action' } }
      ],
      url: {
        list: '/config/tabUpdateConfig/list',
        delete: '/config/tabUpdateConfig/delete',
        deleteBatch: '/config/tabUpdateConfig/deleteBatch',
        exportXlsUrl: '/config/tabUpdateConfig/exportXls',
        importExcelUrl: 'config/tabUpdateConfig/importExcel'
      },
      dictOptions: {}
    }
  },
  computed: {
    importExcelUrl () {
      return `${window._CONFIG['domianURL']}/${this.url.importExcelUrl}`
    }
  },
  methods: {
    initDictConfig () {},
    isEnabled (value) { return String(value) === '1' },
    hasScope (record) {
      return [record.frontendEnabled, record.backendEnabled, record.dbSyncEnabled].some(this.isEnabled)
    },
    canSync (record) {
      return this.isEnabled(record.enabled) && record.updateStatus !== 'RUNNING' && this.hasScope(record)
    },
    statusInfo (value) {
      const statuses = {
        IDLE: { label: '空闲', color: 'default' },
        RUNNING: { label: '更新中', color: 'blue' },
        SUCCESS: { label: '更新成功', color: 'green' },
        FAILED: { label: '更新失败', color: 'red' }
      }
      return statuses[value] || { label: value || '未更新', color: 'default' }
    },
    async handleSyncUpdate (record, retry = false) {
      if (!this.canSync(record)) return
      try {
        const res = await getAction('/config/tabUpdateConfig/syncPreview', { id: record.id })
        if (!res.success) return this.$message.warning(res.message || '配置校验失败')
        if (retry && res.result.retryAllowed === false) return this.$message.warning('失败缓存不可用或恢复未完成，请先人工核对；需要时重新同步')
        this.syncRetry = retry
        this.syncRecord = res.result.config
        this.syncExecution = res.result.execution || {}
        this.syncUpdaterVersion = res.result.updaterVersion || '未上报，请确认后端版本'
        this.syncVisible = true
      } catch (error) { this.$message.error('读取更新配置失败，请检查服务连接') }
    },
    async retryProgressTask () {
      let record = this.dataSource.find(row => String(row.id) === String(this.progressId))
      if (!record) {
        const res = await getAction('/config/tabUpdateConfig/queryById', { id: this.progressId })
        if (!res.success) return this.$message.warning(res.message || '未找到配置')
        record = res.result
      }
      return this.handleSyncUpdate(record, true)
    },
    async cleanupUpdateFiles (record) {
      try {
        const res = await postAction('/config/tabUpdateConfig/cleanup', { id: String(record.id) })
        if (!res.success) return this.$message.warning(res.message || '清理失败')
        const mb = (Number(res.result.freedBytes || 0) / 1048576).toFixed(1)
        this.$message.success('已释放 ' + mb + ' MB；保留最近一次成功备份')
        if (res.result.warnings && res.result.warnings.length) this.$message.warning('部分目录未清理，请检查文件占用')
      } catch (error) { this.$message.error('清理请求失败，请检查连接') }
    },
    async submitSyncUpdate () {
      if (this.syncSubmitting || !this.syncRecord) return
      this.syncSubmitting = true
      try {
        const res = await postAction(this.syncRetry ? '/config/tabUpdateConfig/retryUpdate' : '/config/tabUpdateConfig/syncUpdate', { id: String(this.syncRecord.id) })
        if (!res.success) return this.$message.warning(res.message || '更新提交失败')
        this.$message.success(this.syncRetry ? '失败重试已提交，将优先复用已完成文件' : '更新任务已提交，可查看文件明细和进度')
        this.syncVisible = false
        const updating = { ...this.syncRecord, updateStatus: 'RUNNING' }
        this.beginUpdateProgress(updating)
        this.onClearSelected()
        this.loadData()
      } catch (error) { this.$message.error('未能确认提交结果，请刷新查看状态后再操作，避免重复更新') }
      finally { this.syncSubmitting = false }
    }
  }
}
</script>
<style scoped>
@import '~@assets/less/common.less';
.delete-link { color: #ff4d4f; }
.result-text { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.path-text { word-break: break-all; }
</style>
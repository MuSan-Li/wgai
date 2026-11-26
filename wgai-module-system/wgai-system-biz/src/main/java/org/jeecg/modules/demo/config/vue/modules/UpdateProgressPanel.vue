<template>
  <div class="update-progress-panel">
    <div class="progress-heading"><strong>{{ phaseLabel }}</strong><a-tag>{{ info.status || '待查询' }}</a-tag></div>
    <a-progress :percent="percent" :status="barStatus" />
    <p class="progress-detail">{{ info.detail || '正在获取更新进度' }}</p>
    <a-alert v-if="connectionError" type="warning" show-icon :message="connectionError" class="progress-warning" />
    <div class="progress-metrics">
      <div><span>清单文件</span><strong>{{ info.totalFiles || 0 }}</strong></div>
      <div><span>需要下载</span><strong>{{ info.downloadedFiles || 0 }}</strong></div>
      <div><span>失败缓存</span><strong>{{ info.cachedFiles || 0 }}</strong></div>
      <div><span>本地复用</span><strong>{{ info.reusedFiles || 0 }}</strong></div>
      <div><span>移除旧文件</span><strong>{{ info.removedFiles || 0 }}</strong></div>
    </div>
    <p class="transfer-summary">网络下载 {{ bytes(info.doneBytes) }} / {{ bytes(info.downloadBytes) }} · 已处理 {{ info.doneFiles || 0 }} / {{ info.totalFiles || 0 }} 个文件</p>
    <p v-if="info.lastUpdateTime">结束时间：{{ info.lastUpdateTime }}</p>
    <p v-if="info.lastUpdateMessage" class="result-message">{{ info.lastUpdateMessage }}</p>
    <a-button v-if="info.status === 'FAILED'" type="primary" icon="redo" :disabled="info.retryAllowed === false" @click="$emit('retry')" style="margin-bottom: 12px">失败重试</a-button>
    <update-file-changes :config-id="configId || info.id" :job-key="String(info.startedAt || info.lastUpdateTime || '')" :update-marker="info.updatedAt || info.status" />
    <a-alert type="info" show-icon message="进度自动刷新；服务重启期间会自动重连。本地备份和复用不计入网络下载量。" />
  </div>
</template>
<script>
import UpdateFileChanges from './UpdateFileChanges'
export default {
  components: { UpdateFileChanges },
  name: 'UpdateProgressPanel',
  props: { configId: { type: [String, Number], default: null }, info: { type: Object, default: () => ({}) }, connectionError: { type: String, default: '' } },
  computed: {
    percent () { return Math.max(0, Math.min(100, Number(this.info.percent) || 0)) },
    barStatus () { return this.info.status === 'FAILED' ? 'exception' : this.info.status === 'SUCCESS' ? 'success' : 'active' },
    phaseLabel () {
      return ({ QUEUED: '任务排队', MANIFEST: '读取发布清单', COMPARE: '比对本地文件', PLANNED: '差异统计完成',
        TRANSFER: '下载与本地复用', BACKUP: '备份本机文件', DATABASE: '同步数据库', HANDOFF: '准备切换',
        HEALTH_CHECK: '更新前健康检查', PREPARE_SWITCH: '准备切换文件', STOPPING: '停止后端服务', SWITCHING: '切换文件',
        STARTING: '启动及健康检查', CLEANUP: '清理暂存内容', SUCCESS: '更新完成', FAILED: '更新失败' })[this.info.phase] || '等待更新状态'
    }
  },
  methods: {
    bytes (value) {
      const size = Number(value) || 0
      if (size >= 1073741824) return (size / 1073741824).toFixed(2) + ' GB'
      if (size >= 1048576) return (size / 1048576).toFixed(1) + ' MB'
      if (size >= 1024) return (size / 1024).toFixed(1) + ' KB'
      return size + ' B'
    }
  }
}
</script>
<style scoped>
.progress-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.progress-detail, .result-message { white-space: pre-wrap; overflow-wrap: anywhere; color: #595959; }
.progress-metrics { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin: 20px 0 12px; }
.progress-metrics div { padding: 12px; background: #f5f8fc; border-radius: 6px; }
.progress-metrics span { display: block; color: #8c8c8c; font-size: 12px; }
.progress-metrics strong { display: block; margin-top: 4px; font-size: 22px; color: #262626; }
.transfer-summary { color: #595959; }
.progress-warning { margin-bottom: 12px; }
@media (max-width: 600px) { .progress-metrics { grid-template-columns: repeat(2, 1fr); } }
</style>

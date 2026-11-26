<template>
  <div class="file-changes">
    <div class="file-heading"><strong>文件更新明细</strong>
      <a-select v-model="action" size="small" style="width: 150px" @change="reset">
        <a-select-option value="CHANGED">只看变更文件</a-select-option>
        <a-select-option value="">全部文件</a-select-option>
        <a-select-option value="ADD">新增</a-select-option>
        <a-select-option value="REPLACE">替换</a-select-option>
        <a-select-option value="REMOVE">移除</a-select-option>
        <a-select-option value="REUSE">未变 / 本地复用</a-select-option>
      </a-select>
    </div>
    <a-alert v-if="error" type="warning" :message="error" show-icon style="margin-bottom: 8px" />
    <a-table size="small" :columns="columns" :data-source="rows" :row-key="row => row.kind + ':' + row.path" :loading="loading"
      :pagination="{ current: page, pageSize: 20, total, showSizeChanger: false }" :scroll="{ x: 780, y: 300 }" @change="changePage">
      <template slot="module" slot-scope="text">{{ text === 'backend' ? '后端' : '前端' }}</template>
      <template slot="file" slot-scope="text, record"><a-tooltip :title="'目标 SHA256：' + (record.sha256 || '—') + '\n本地原 SHA256：' + (record.previousSha256 || '未记录')"><span class="file-path">{{ text }}</span></a-tooltip></template>
      <template slot="action" slot-scope="text"><a-tag :color="text === 'REMOVE' ? 'orange' : text === 'ADD' ? 'green' : text === 'REPLACE' ? 'blue' : undefined">{{ actionName(text) }}</a-tag></template>
      <template slot="source" slot-scope="text">{{ ({ NETWORK: '网络下载', CACHE: '失败缓存', LOCAL: '本地复用', NONE: '—' })[text] || text }}</template>
      <template slot="state" slot-scope="text">{{ ({ PLANNED: '待处理', READY: '已校验待安装', APPLIED: '已生效', UNCHANGED: '未改动', NOT_APPLIED: '未生效 / 已恢复', CHECK_REQUIRED: '需人工核对' })[text] || text }}</template>
      <template slot="bytes" slot-scope="text">{{ bytes(text) }}</template>
    </a-table>
    <p class="file-note">比对完成后显示计划；“已校验”表示准备完成，“已生效”仅在整次更新成功后显示。悬停文件名可查看目标与原文件 SHA256。</p>
  </div>
</template>
<script>
import { axios } from '@/utils/request'
import signMd5Utils from '@/utils/encryption/signMd5Utils'
export default {
  name: 'UpdateFileChanges',
  props: { configId: { type: [String, Number], default: null }, jobKey: { type: String, default: '' }, updateMarker: { type: [String, Number], default: '' } },
  data () {
    return { rows: [], page: 1, total: 0, action: 'CHANGED', loading: false, error: '', epoch: 0, active: true, pending: false,
      columns: [
        { title: '模块', dataIndex: 'kind', width: 65, scopedSlots: { customRender: 'module' } },
        { title: '文件', dataIndex: 'path', width: 260, scopedSlots: { customRender: 'file' } },
        { title: '变更', dataIndex: 'action', width: 85, scopedSlots: { customRender: 'action' } },
        { title: '来源', dataIndex: 'source', width: 90, scopedSlots: { customRender: 'source' } },
        { title: '状态', dataIndex: 'state', width: 125, scopedSlots: { customRender: 'state' } },
        { title: '大小', dataIndex: 'size', width: 85, scopedSlots: { customRender: 'bytes' } }
      ] }
  },
  watch: { configId: { immediate: true, handler () { this.reset() } }, jobKey () { this.reset() }, updateMarker () { this.load() } },
  beforeDestroy () { this.active = false; this.epoch += 1 },
  methods: {
    reset () { this.epoch += 1; this.loading = false; this.pending = false; this.rows = []; this.page = 1; this.load() },
    changePage (pagination) { this.epoch += 1; this.loading = false; this.page = pagination.current; this.load() },
    async load () {
      if (!this.active || !this.configId) return
      if (this.loading) { this.pending = true; return }
      this.loading = true
      const epoch = this.epoch
      const url = '/config/tabUpdateConfig/fileChanges'
      const params = { id: String(this.configId), page: this.page, size: 20, action: this.action }
      try {
        const res = await axios({ url, method: 'get', params, timeout: 5000, silentError: true,
          headers: { 'X-Sign': signMd5Utils.getSign(url, params), 'X-TIMESTAMP': signMd5Utils.getTimestamp() } })
        if (!this.active || epoch !== this.epoch) return
        if (!res.success) throw new Error(res.message || '明细查询失败')
        this.rows = res.result.records || []; this.total = res.result.total || 0; this.error = ''
      } catch (error) {
        if (this.active && epoch === this.epoch) this.error = '明细暂不可达，保留最近结果；服务重启后自动重连'
      } finally {
        if (this.active && epoch === this.epoch) { this.loading = false; if (this.pending) { this.pending = false; this.load() } }
      }
    },
    actionName (value) { return ({ ADD: '新增', REPLACE: '替换', REMOVE: '移除', REUSE: '未变' })[value] || value },
    bytes (value) { const size = Number(value) || 0; return size >= 1048576 ? (size / 1048576).toFixed(1) + ' MB' : size >= 1024 ? (size / 1024).toFixed(1) + ' KB' : size + ' B' }
  }
}
</script>
<style scoped>
.file-changes { margin: 16px 0; }
.file-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.file-path { white-space: normal; word-break: break-all; }
.file-note { margin-top: 8px; color: #8c8c8c; font-size: 12px; }
</style>

import { axios } from '@/utils/request'
import signMd5Utils from '@/utils/encryption/signMd5Utils'
export const UpdateProgressMixin = {
  data () {
    return { progressMap: {}, progressErrors: {}, progressVisible: false, progressId: null, progressBusy: false, progressActive: false, progressEpoch: 0, progressTimer: null }
  },
  computed: {
    runningProgressKey () { return this.dataSource.filter(row => row.updateStatus === 'RUNNING').map(row => String(row.id)).join(',') },
    selectedProgress () { return this.progressMap[String(this.progressId)] || {} }
  },
  mounted () { this.startProgressPolling() },
  activated () { this.startProgressPolling() },
  deactivated () { this.stopProgressPolling() },
  beforeDestroy () { this.stopProgressPolling() },
  watch: { runningProgressKey () { this.refreshUpdateProgress() } },
  methods: {
    startProgressPolling () {
      if (this.progressActive) return
      this.progressActive = true
      this.progressEpoch += 1
      this.progressTimer = setInterval(() => this.refreshUpdateProgress(), 2000)
      document.addEventListener('visibilitychange', this.onProgressVisibility)
      this.refreshUpdateProgress()
    },
    stopProgressPolling () {
      this.progressActive = false
      this.progressEpoch += 1
      this.progressBusy = false
      clearInterval(this.progressTimer)
      document.removeEventListener('visibilitychange', this.onProgressVisibility)
    },
    onProgressVisibility () { if (!document.hidden) this.refreshUpdateProgress() },
    beginUpdateProgress (record) {
      this.progressEpoch += 1
      this.progressBusy = false
      this.$set(this.progressMap, String(record.id), { status: 'RUNNING', phase: 'QUEUED', percent: 0 })
      this.$set(this.progressErrors, String(record.id), '')
      this.showUpdateProgress(record)
    },
    showUpdateProgress (record) {
      this.progressId = String(record.id)
      this.progressVisible = true
      if (!this.progressMap[this.progressId]) this.$set(this.progressMap, this.progressId, { status: record.updateStatus, percent: record.updateStatus === 'SUCCESS' ? 100 : 0 })
      this.refreshUpdateProgress(this.progressId)
    },
    async refreshUpdateProgress (forceId) {
      if (!this.progressActive || this.progressBusy || document.hidden) return
      const ids = this.runningProgressKey ? this.runningProgressKey.split(',') : []
      if (forceId) ids.push(String(forceId))
      if (this.progressVisible && this.selectedProgress.status === 'RUNNING') ids.push(String(this.progressId))
      const unique = [...new Set(ids)]
      if (!unique.length) return
      this.progressBusy = true
      const epoch = this.progressEpoch
      try {
        await Promise.all(unique.map(async id => {
          const url = '/config/tabUpdateConfig/progress'
          const params = { id }
          try {
            const res = await axios({ url, method: 'get', params, timeout: 5000, silentError: true,
              headers: { 'X-Sign': signMd5Utils.getSign(url, params), 'X-TIMESTAMP': signMd5Utils.getTimestamp() } })
            if (!this.progressActive || epoch !== this.progressEpoch) return
            if (!res.success || !res.result) throw new Error(res.message || '进度响应无效')
            this.$set(this.progressMap, id, res.result)
            this.$set(this.progressErrors, id, '')
            const row = this.dataSource.find(item => String(item.id) === id)
            if (row) ['status', 'currentVersion', 'lastUpdateTime', 'lastUpdateMessage'].forEach(key => {
              if (res.result[key] !== undefined) this.$set(row, key === 'status' ? 'updateStatus' : key, res.result[key])
            })
          } catch (error) {
            if (!this.progressActive || epoch !== this.progressEpoch) return
            const status = error.response && error.response.status
            this.$set(this.progressErrors, id, status === 404 ? '进度接口未部署，请更新后端业务 JAR' : '后台重启或暂不可达，正在自动重连；保留最近一次进度')
          }
        }))
      } finally {
        if (epoch === this.progressEpoch) this.progressBusy = false
      }
    }
  }
}

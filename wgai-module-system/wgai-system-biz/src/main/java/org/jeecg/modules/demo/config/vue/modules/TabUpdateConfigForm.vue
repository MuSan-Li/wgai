<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model slot="detail" ref="form" :model="model" :rules="validatorRules" layout="vertical" class="update-config-form">
        <div class="section-heading">基本信息</div>
        <p class="section-description">设置配置名称、版本来源及启用状态。</p>
        <a-row :gutter="24">
          <a-col :xs="24" :md="12">
            <a-form-model-item label="配置名称" prop="configName">
              <a-input v-model="model.configName" placeholder="例如：生产环境更新配置" :max-length="100" />
            </a-form-model-item>
          </a-col>
          <a-col :xs="24" :md="8"><a-form-model-item label="启用配置">
            <a-switch :checked="model.enabled === 1" checked-children="开" un-checked-children="关" @change="value => model.enabled = value ? 1 : 0" />
          </a-form-model-item></a-col>
        </a-row>
        <a-row :gutter="24">
          <a-col :xs="24" :md="12">
            <a-form-model-item label="当前版本" prop="currentVersion" extra="新增时填写已部署版本；后续由更新任务维护。">
              <a-input v-model="model.currentVersion" placeholder="例如：1.0.0" :max-length="64" :disabled="!!model.id && !!originalVersion" />
            </a-form-model-item>
          </a-col>
          <a-col :xs="24" :md="12">
            <a-form-model-item label="版本清单地址" prop="releaseManifestUrl">
              <a-input v-model="model.releaseManifestUrl" placeholder="https://zhy116.com/wgai/bbk/latest.json" :max-length="2000" />
            </a-form-model-item>
          </a-col>
        </a-row>
        <a-form-model-item label="部署系统与默认目录" extra="新增时按后端服务器系统填入默认值；切换系统或点击按钮会重新填入目录，所有地址均可修改；新增和编辑均检查真实目录，通过后在所填地址创建缺失脚本、发布清单和后端健康接口。">
          <a-radio-group :value="deploymentPlatform" @change="event => applyDirectoryDefaults(event.target.value)">
            <a-radio-button value="windows">Windows</a-radio-button>
            <a-radio-button value="linux">Linux</a-radio-button>
          </a-radio-group>
          <a-button style="margin-left: 12px" @click="applyDirectoryDefaults(deploymentPlatform)">填入默认目录</a-button>
          <a-button style="margin-left: 12px" :loading="pathChecking" @click="checkDeploymentPaths">检查全部配置</a-button>
        </a-form-model-item>
        <a-alert v-if="pathCheck" class="path-check-result" :type="pathCheck.success ? 'success' : 'error'" show-icon :message="pathCheck.message" :description="pathCheck.detail" />
        <div class="section-heading">下载与运行设置</div>
        <p class="section-description">页面填写的值优先使用；留空读取 YML。Java 留空时可自动沿用当前服务的 Java，无需固定安装目录。</p>
        <a-row :gutter="24">
          <a-col :xs="24" :md="12">
            <a-form-model-item label="下载域名白名单" prop="allowedHosts" extra="只填域名或 IP，多个用英文逗号分隔，不填协议和端口。">
              <a-input v-model="model.allowedHosts" allow-clear placeholder="例如：zhy116.com" :max-length="2000" />
            </a-form-model-item>
          </a-col>
          <a-col :xs="24" :md="12">
            <a-form-model-item label="更新器 Java 路径（可留空）" prop="javaExecutable" extra="默认沿用当前 Java；需要指定其他版本时填写 java.exe 或 java 的绝对路径。">
              <a-input v-model="model.javaExecutable" allow-clear placeholder="留空自动选择当前 Java" :max-length="1000" />
            </a-form-model-item>
          </a-col>
        </a-row>
        <div class="section-heading">前端更新</div>
        <p class="section-description">支持 Windows/Linux 本机部署；只下载新增或变化文件，默认保留本机 static 整个目录（含机器 IP 配置）。</p>
        <a-row :gutter="24">
          <a-col :xs="24" :md="8"><a-form-model-item label="更新前端">
            <a-switch :checked="model.frontendEnabled === 1" checked-children="开" un-checked-children="关" @change="value => model.frontendEnabled = value ? 1 : 0" />
          </a-form-model-item></a-col>
        </a-row>
        <a-row :gutter="24" v-if="model.frontendEnabled === 1">
          <a-col :xs="24" :md="12">
            <a-form-model-item label="前端部署目录" prop="frontendDir" extra="必须是服务器上已部署、包含 index.html 的真实目录。">
              <a-input v-model="model.frontendDir" placeholder="例如：/opt/app/frontend 或 D:/app/frontend" :max-length="1000" />
            </a-form-model-item>
          </a-col>
          <a-col :xs="24" :md="12">
            <a-form-model-item label="前端资源地址（可留空）" prop="frontendDownloadUrl">
              <a-input v-model="model.frontendDownloadUrl" placeholder="散文件填目录URL；ZIP填文件URL；留空使用清单" :max-length="2000" />
            </a-form-model-item>
          </a-col>
        </a-row>
        <a-form-model-item v-if="model.frontendEnabled === 1" label="前端保留目录" prop="frontendPreservePaths" extra="相对前端目录，多个用英文逗号分隔；默认保留整个 static。发布端已排除的目录也必须包含在保留范围内。">
          <a-input v-model="model.frontendPreservePaths" allow-clear placeholder="static 或 static,device-config；留空使用 YML" :max-length="2000" />
        </a-form-model-item>
        <div class="section-heading">后端更新</div>
        <p class="section-description">支持 jar 目录散文件，400 多个 JAR 也只下载新增或变化项；切换前备份，保留启动 JAR。</p>
        <a-row :gutter="24">
          <a-col :xs="24" :md="8"><a-form-model-item label="更新后端 lib">
            <a-switch :checked="model.backendEnabled === 1" checked-children="开" un-checked-children="关" @change="value => model.backendEnabled = value ? 1 : 0" />
          </a-form-model-item></a-col>
        </a-row>
        <a-row :gutter="24" v-if="model.backendEnabled === 1">
          <a-col :xs="24" :md="12">
            <a-form-model-item label="lib 目录" prop="backendLibDir" extra="必须是服务器上正在使用、包含 JAR 文件的真实依赖目录。">
              <a-input v-model="model.backendLibDir" placeholder="例如：/opt/app/lib 或 D:/app/lib" :max-length="1000" />
            </a-form-model-item>
          </a-col>
          <a-col :xs="24" :md="12">
            <a-form-model-item label="启动 JAR 路径" prop="launcherJarPath" extra="填写已存在的启动 JAR 文件；启动时自动切换到它所在的目录。">
              <a-input v-model="model.launcherJarPath" placeholder="例如：/opt/app/launcher.jar" :max-length="1000" />
            </a-form-model-item>
          </a-col>
          <a-col :xs="24" :md="24">
            <a-form-model-item label="后端资源地址（可留空）" prop="backendDownloadUrl">
              <a-input v-model="model.backendDownloadUrl" placeholder="散文件填目录URL；ZIP填文件URL；留空使用清单" :max-length="2000" />
            </a-form-model-item>
          </a-col>
        </a-row>
        <div v-if="model.backendEnabled === 1" class="execution-settings">
          <p class="section-description">启停脚本地址不能为空，已填入明确的默认目录，可自行修改。“检查全部配置”通过后在所填路径创建缺失脚本；已有脚本原样保留，不会切换为其他文件。</p>
          <a-row :gutter="24">
            <a-col :xs="24" :md="12">
              <a-form-model-item label="停止脚本路径" prop="stopScript" extra="自动生成的脚本仅匹配当前启动 JAR 和 lib，脚本需放在部署、下载和备份目录之外。">
                <a-input v-model="model.stopScript" allow-clear placeholder="请填写完整的 stop.bat 或 stop.sh 路径" :max-length="1000" />
              </a-form-model-item>
            </a-col>
            <a-col :xs="24" :md="12">
              <a-form-model-item label="启动脚本路径" prop="startScript" extra="Windows 新开前台黑窗口运行；Linux 后台运行，日志写入启动 JAR 目录的 platform-start.log。">
                <a-input v-model="model.startScript" allow-clear placeholder="请填写完整的 start.bat 或 start.sh 路径" :max-length="1000" />
              </a-form-model-item>
            </a-col>
            <a-col :xs="24" :md="24">
              <a-form-model-item label="后端健康检查地址" prop="healthUrl" extra="新增/编辑均实际访问此接口，必须免登录返回 HTTP 200；留空使用 YML。">
                <a-input v-model="model.healthUrl" allow-clear placeholder="http://127.0.0.1:9998/实际健康检查路径" :max-length="2000" />
              </a-form-model-item>
            </a-col>
          </a-row>
        </div>
        <div class="section-heading">数据库结构与基础数据</div>
        <p class="section-description">schema.sql 比对全部表结构；可选 base-data.sql 补充字典、菜单及自定义表数据，仅新增、不覆盖已有记录。</p>
        <a-row :gutter="24">
          <a-col :xs="24" :md="8"><a-form-model-item label="同步结构及基础数据">
            <a-switch :checked="model.dbSyncEnabled === 1" checked-children="开" un-checked-children="关" @change="value => model.dbSyncEnabled = value ? 1 : 0" />
          </a-form-model-item></a-col>
        </a-row>
        <a-row :gutter="24" v-if="model.dbSyncEnabled === 1">
          <a-col :xs="24" :md="12">
            <a-form-model-item label="数据源标识" prop="dbDatasourceKey">
              <a-input v-model="model.dbDatasourceKey" placeholder="例如：master" :max-length="100" />
            </a-form-model-item>
          </a-col>
          <a-col :xs="24" :md="12">
            <a-form-model-item label="同步表名前缀" prop="dbTablePrefix">
              <a-input v-model="model.dbTablePrefix" placeholder="例如：tab_；留空表示全部业务表" :max-length="255" />
            </a-form-model-item>
          </a-col>
          <a-col :xs="24" :md="24">
            <a-form-model-item label="完整结构 SQL 下载地址" prop="dbSchemaUrl">
              <a-input v-model="model.dbSchemaUrl" placeholder="留空则使用版本清单中的地址" :max-length="2000" />
            </a-form-model-item>
          </a-col>
        </a-row>
        <div v-if="model.dbSyncEnabled === 1" class="database-policy">
          <a-row :gutter="24">
          <a-col :xs="24" :md="8"><a-form-model-item label="允许修改已有字段">
            <a-switch :checked="model.dbAllowModifyColumn === 1" checked-children="开" un-checked-children="关" @change="value => model.dbAllowModifyColumn = value ? 1 : 0" />
          </a-form-model-item></a-col>
          <a-col :xs="24" :md="8"><a-form-model-item label="允许删除多余字段">
            <a-switch :checked="model.dbAllowDropColumn === 1" checked-children="开" un-checked-children="关" @change="value => model.dbAllowDropColumn = value ? 1 : 0" />
          </a-form-model-item></a-col>
          <a-col :xs="24" :md="8"><a-form-model-item label="允许删除多余表">
            <a-switch :checked="model.dbAllowDropTable === 1" checked-children="开" un-checked-children="关" @change="value => model.dbAllowDropTable = value ? 1 : 0" />
          </a-form-model-item></a-col>
        </a-row>
          <a-alert v-if="model.dbAllowModifyColumn || model.dbAllowDropColumn || model.dbAllowDropTable" type="warning" show-icon message="结构修改或删除可能影响现有数据，请先完成数据库备份。文件恢复不会回滚数据库。" />
        </div>
        <div class="section-heading">备份与下载目录</div>
        <p class="section-description">请填写部署服务器上的绝对路径；下载、备份和部署目录不能重叠。</p>
        <a-row :gutter="24">
          <a-col :xs="24" :md="12">
            <a-form-model-item label="备份目录" prop="backupDir">
              <a-input v-model="model.backupDir" placeholder="例如：/opt/app-update/backup" :max-length="1000" />
            </a-form-model-item>
          </a-col>
          <a-col :xs="24" :md="12">
            <a-form-model-item label="下载暂存目录" prop="downloadDir">
              <a-input v-model="model.downloadDir" placeholder="例如：/opt/app-update/download" :max-length="1000" />
            </a-form-model-item>
          </a-col>
        </a-row>
        <a-form-model-item label="备注" prop="remark">
          <a-textarea v-model="model.remark" :rows="2" :max-length="1000" placeholder="可填写部署环境及维护说明" />
        </a-form-model-item>
        <template v-if="model.id">
          <div class="section-heading">最近更新结果</div>
          <a-descriptions bordered size="small" :column="2">
            <a-descriptions-item label="更新状态">{{ statusLabel }}</a-descriptions-item>
            <a-descriptions-item label="结束时间">{{ model.lastUpdateTime || '—' }}</a-descriptions-item>
            <a-descriptions-item label="执行结果" :span="2"><span class="result-message">{{ model.lastUpdateMessage || '暂无更新记录' }}</span></a-descriptions-item>
          </a-descriptions>
        </template>
      </a-form-model>
    </j-form-container>
  </a-spin>
</template>
<script>
import { httpAction, getAction } from '@/api/manage'
const defaults = () => ({
  frontendEnabled: 1, backendEnabled: 1, dbSyncEnabled: 0,
  dbAllowModifyColumn: 0, dbAllowDropColumn: 0, dbAllowDropTable: 0,
  stopScript: '', startScript: '', healthUrl: '', allowedHosts: '', frontendPreservePaths: '', javaExecutable: '',
  dbDatasourceKey: 'master', enabled: 1, updateStatus: 'IDLE'
})
const directoryDefaults = platform => {
  const windows = platform === 'windows'
  const base = windows ? 'D:/Ailelon' : '/home/javaai'
  const backend = windows ? base + '/jar' : base
  return {
    frontendDir: windows ? base + '/nginx-1.20.0/html' : '/var/www',
    backendLibDir: backend + '/lib', launcherJarPath: backend + '/wgai-system-start-3.4.3.jar',
    backupDir: base + '/backup', downloadDir: base + '/load',
    stopScript: backend + '/control/stop' + (windows ? '.bat' : '.sh'), startScript: backend + '/control/start' + (windows ? '.bat' : '.sh'),
    healthUrl: 'http://127.0.0.1:9998/wgai/tab/tabAiHistory/list'
  }
}
const scriptDefaults = (launcher, platform) => {
  const jar = String(launcher || '').replace(/\\/g, '/')
  const split = jar.lastIndexOf('/')
  const root = split > 0 ? jar.slice(0, split) : directoryDefaults(platform).launcherJarPath.replace(/\/[^/]+$/, '')
  const extension = platform === 'linux' ? '.sh' : '.bat'
  return { startScript: root + '/control/start' + extension, stopScript: root + '/control/stop' + extension }
}
export default {
  name: 'TabUpdateConfigForm',
  props: { disabled: { type: Boolean, default: false } },
  data () {
    const required = message => [{ required: true, whitespace: true, message, trigger: 'blur' }]
    return {
      model: defaults(), originalVersion: null, confirmLoading: false, pathChecking: false, pathCheck: null, deploymentPlatform: 'windows', defaultsEpoch: 0,
      validatorRules: {
        configName: required('请输入配置名称'),
        currentVersion: required('请输入当前已部署版本'),
        releaseManifestUrl: required('请输入版本清单地址'),
        frontendDir: required('请输入前端部署目录'),
        backendLibDir: required('请输入后端 lib 目录'),
        launcherJarPath: required('请输入启动 JAR 路径'),
        startScript: required('请输入启动脚本完整路径'),
        stopScript: required('请输入停止脚本完整路径'),
        dbDatasourceKey: required('请输入数据源标识'),
        backupDir: required('请输入备份目录'),
        downloadDir: required('请输入下载暂存目录')
      },
      url: { add: '/config/tabUpdateConfig/add', edit: '/config/tabUpdateConfig/edit' }
    }
  },
  computed: {
    formDisabled () { return this.disabled || this.confirmLoading || this.pathChecking || this.model.updateStatus === 'RUNNING' },
    pathFingerprint () {
      return JSON.stringify([this.model.frontendEnabled, this.model.backendEnabled, this.model.dbSyncEnabled,
        this.model.frontendDir, this.model.backendLibDir, this.model.launcherJarPath, this.model.downloadDir,
        this.model.backupDir, this.model.stopScript, this.model.startScript, this.model.healthUrl,
        this.model.allowedHosts, this.model.releaseManifestUrl, this.model.frontendPreservePaths])
    },
    statusLabel () { return ({ IDLE: '空闲', RUNNING: '更新中', SUCCESS: '更新成功', FAILED: '更新失败' })[this.model.updateStatus] || '未更新' }
  },
  watch: {
    pathFingerprint () { this.pathCheck = null },
    'model.launcherJarPath' (value, previous) {
      const before = scriptDefaults(previous, this.deploymentPlatform)
      const after = scriptDefaults(value, this.deploymentPlatform)
      ;['startScript', 'stopScript'].forEach(key => { if (this.model[key] === before[key]) this.$set(this.model, key, after[key]) })
    }
  },
  methods: {
    async checkDeploymentPaths () {
      if (this.formDisabled) return
      if (this.model.backendEnabled === 1 && (!String(this.model.startScript || '').trim() || !String(this.model.stopScript || '').trim())) return this.$message.warning('请填写启动和停止脚本完整路径')
      const epoch = this.defaultsEpoch
      this.pathChecking = true
      this.pathCheck = null
      try {
        const res = await httpAction('/config/tabUpdateConfig/validateConfig', { ...this.model }, 'post')
        if (epoch !== this.defaultsEpoch) return
        const result = res.result || {}
        const execution = result.execution || {}
        const details = (result.checks || []).map(label => '✓ ' + label)
        if (res.success && this.model.backendEnabled === 1) details.push(`启动脚本：${execution.startScript || '—'}`, `停止脚本：${execution.stopScript || '—'}`)
        ;(result.createdScripts || []).forEach(path => details.push(`已创建：${path}`))
        ;(result.existingScripts || []).forEach(path => details.push(`已有，保持不变：${path}`))
        const detail = details.join('\n')
        this.pathCheck = { success: !!res.success, message: result.message || res.message || '路径检查失败', detail }
      } catch (error) {
        if (epoch === this.defaultsEpoch) this.pathCheck = { success: false, message: '配置检查请求失败，请检查服务连接', detail: '' }
      } finally { if (epoch === this.defaultsEpoch) this.pathChecking = false }
    },
    async add () {
      this.edit({ ...defaults(), ...directoryDefaults('windows'), allowedHosts: 'zhy116.com', frontendPreservePaths: 'static', releaseManifestUrl: 'https://zhy116.com/wgai/bbk/latest.json' })
      const epoch = this.defaultsEpoch
      this.confirmLoading = true
      try {
        const res = await getAction('/config/tabUpdateConfig/defaults')
        if (epoch === this.defaultsEpoch && res.success && res.result) {
          this.deploymentPlatform = res.result.platform || 'windows'
          const values = { ...res.result }
          delete values.platform
          Object.keys(values).forEach(key => this.$set(this.model, key, values[key]))
        }
      } catch (error) {
        if (epoch === this.defaultsEpoch) this.$message.warning('未能读取服务器系统，已填入 Windows 默认目录，可切换系统后修改')
      } finally { if (epoch === this.defaultsEpoch) this.confirmLoading = false }
    },
    applyDirectoryDefaults (platform) {
      this.defaultsEpoch += 1
      this.confirmLoading = false
      this.pathChecking = false
      this.pathCheck = null
      this.deploymentPlatform = platform
      Object.keys(directoryDefaults(platform)).forEach(key => this.$set(this.model, key, directoryDefaults(platform)[key]))
    },
    edit (record) {
      this.defaultsEpoch += 1
      this.confirmLoading = false
      this.pathChecking = false
      this.pathCheck = null
      this.deploymentPlatform = String(record.backendLibDir || record.frontendDir || '').startsWith('/') ? 'linux' : 'windows'
      this.model = { ...defaults(), ...record }
      this.originalVersion = record.currentVersion || null
      ;['stopScript', 'startScript', 'healthUrl', 'allowedHosts', 'frontendPreservePaths', 'javaExecutable'].forEach(key => {
        this.model[key] = this.model[key] || ''
      })
      const scripts = scriptDefaults(this.model.launcherJarPath, this.deploymentPlatform)
      ;['startScript', 'stopScript'].forEach(key => { if (!String(this.model[key] || '').trim()) this.$set(this.model, key, scripts[key]) })
      ;['frontendEnabled', 'backendEnabled', 'dbSyncEnabled', 'dbAllowModifyColumn', 'dbAllowDropColumn', 'dbAllowDropTable', 'enabled'].forEach(key => {
        this.model[key] = Number(this.model[key]) === 1 ? 1 : 0
      })
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    submitForm () {
      if (this.formDisabled || this.confirmLoading) return
      if (![this.model.frontendEnabled, this.model.backendEnabled, this.model.dbSyncEnabled].some(value => value === 1)) {
        return this.$message.warning('请至少选择一个更新范围')
      }
      if (this.model.backendEnabled === 1 && (!String(this.model.startScript || '').trim() || !String(this.model.stopScript || '').trim())) return this.$message.warning('请填写启动和停止脚本完整路径')
      this.$refs.form.validate(async valid => {
        if (!valid) return
        const epoch = this.defaultsEpoch
        this.confirmLoading = true
        try {
          const checked = await httpAction('/config/tabUpdateConfig/validateConfig', { ...this.model }, 'post')
          if (epoch !== this.defaultsEpoch) return
          const report = checked.result || {}
          this.pathCheck = { success: !!checked.success, message: report.message || checked.message || '配置检查失败', detail: (report.checks || []).map(label => '✓ ' + label).join('\n') }
          if (!checked.success) return this.$message.warning(checked.message || '请先修正未通过的检查项')
          const editing = !!this.model.id
          const res = await httpAction(editing ? this.url.edit : this.url.add, { ...this.model }, editing ? 'put' : 'post')
          if (res.success) { this.$message.success(res.message || '保存成功'); this.$emit('ok') }
          else this.$message.warning(res.message || '保存失败')
        } catch (error) { this.$message.error('保存失败，请检查服务连接') }
        finally { if (epoch === this.defaultsEpoch) this.confirmLoading = false }
      })
    }
  }
}
</script>
<style scoped>
.path-check-result { margin: 12px 0; white-space: pre-line; word-break: break-all; }
.update-config-form { padding: 0 8px 12px; }
.section-heading { margin: 22px 0 8px; padding-left: 10px; border-left: 3px solid #1890ff; color: #262626; font-size: 16px; font-weight: 600; }
.section-heading:first-child { margin-top: 0; }
.section-description { margin-bottom: 16px; color: #8c8c8c; font-size: 13px; }
.database-policy { margin-bottom: 20px; padding: 16px; background: #fafafa; border: 1px solid #f0f0f0; border-radius: 4px; }
.result-message { white-space: pre-wrap; word-break: break-all; }
</style>

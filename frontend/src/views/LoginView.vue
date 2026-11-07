<script setup>
import { computed, nextTick, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ArchiveMark from '../components/ArchiveMark.vue'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const formRef = ref()
const usernameInput = ref()
const mode = ref('login')
const loading = ref(false)
const errorMessage = ref('')

const form = reactive({
  username: '',
  email: '',
  password: '',
})

const isRegister = computed(() => mode.value === 'register')
const heading = computed(() => (isRegister.value ? '创建账号' : '登录'))
const supportingText = computed(() =>
  isRegister.value
    ? '为你的网页档案建立一个独立账号。'
    : '继续保存、检索和阅读你的网页档案。',
)

const rules = computed(() => ({
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  email: isRegister.value
    ? [
        { required: true, message: '请输入邮箱', trigger: 'blur' },
        { type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' },
      ]
    : [],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}))

function switchMode(nextMode) {
  if (loading.value) return
  mode.value = nextMode
  errorMessage.value = ''
  form.password = ''
  nextTick(() => {
    formRef.value?.clearValidate()
    usernameInput.value?.focus()
  })
}

function clearFieldError(prop) {
  errorMessage.value = ''
  formRef.value?.clearValidate(prop)
}

async function submit() {
  errorMessage.value = ''
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    if (isRegister.value) {
      await auth.register(form.username.trim(), form.email.trim(), form.password)
      ElMessage.success('注册成功，请登录')
      mode.value = 'login'
      form.password = ''
      await nextTick()
      formRef.value?.clearValidate()
      usernameInput.value?.focus()
      return
    }

    await auth.login(form.username.trim(), form.password)
    await router.push('/links')
  } catch (error) {
    errorMessage.value = error.message || '请求失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="intro-panel" aria-labelledby="product-title">
      <header class="product-identity">
        <ArchiveMark :size="32" />
        <h1 id="product-title">WebCollector</h1>
      </header>

      <div class="intro-copy">
        <p class="manifesto">把网页保存成<br />以后仍然可读的证据。</p>
        <p class="supporting-copy">
          保存 HTML、PDF、截图和可读正文。即使原网页变化，你仍能回看当时的内容。
        </p>
      </div>

      <figure
        class="archive-preview"
        aria-label="保存后生成可读正文、完整网页、打印副本和页面截图的证据示意"
      >
        <div class="archive-card">
          <header class="archive-header">
            <span>保存档案</span>
            <span class="readable-status"><span aria-hidden="true"></span>正文可读</span>
          </header>

          <div class="archive-body">
            <p class="archive-lead">保存当时仍然可见的内容</p>
            <p class="archive-copy">
              正文用于阅读，HTML、PDF 和截图用于核对原始页面。每一种副本都保留自己的用途。
            </p>
          </div>

          <div class="evidence-band">
            <div class="evidence-item">
              <strong>HTML</strong>
              <span>完整网页</span>
            </div>
            <div class="evidence-item">
              <strong>PDF</strong>
              <span>打印副本</span>
            </div>
            <div class="evidence-item">
              <strong>PNG</strong>
              <span>页面截图</span>
            </div>
          </div>
        </div>
      </figure>
    </section>

    <section class="auth-panel">
      <div class="auth-content">
        <div class="auth-heading">
          <h2>{{ heading }}</h2>
          <p>{{ supportingText }}</p>
        </div>

        <el-form
          ref="formRef"
          class="auth-form"
          :model="form"
          :rules="rules"
          label-position="top"
          :aria-busy="loading"
          @submit.prevent="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              ref="usernameInput"
              v-model="form.username"
              name="username"
              autocomplete="username"
              placeholder="输入用户名"
              size="large"
              @input="clearFieldError('username')"
            />
          </el-form-item>

          <el-form-item v-if="isRegister" label="邮箱" prop="email">
            <el-input
              v-model="form.email"
              name="email"
              type="email"
              autocomplete="email"
              placeholder="输入邮箱地址"
              size="large"
              @input="clearFieldError('email')"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              name="password"
              type="password"
              show-password
              :autocomplete="isRegister ? 'new-password' : 'current-password'"
              placeholder="输入密码"
              size="large"
              @input="clearFieldError('password')"
            />
          </el-form-item>

          <p v-if="errorMessage" class="form-error" role="alert">
            {{ errorMessage }}
          </p>

          <el-button
            class="submit-button"
            type="primary"
            native-type="submit"
            size="large"
            :loading="loading"
            :disabled="loading"
          >
            {{ isRegister ? '创建账号' : '登录' }}
          </el-button>
        </el-form>

        <p class="mode-switch">
          <span>{{ isRegister ? '已有账号？' : '没有账号？' }}</span>
          <el-button
            link
            :disabled="loading"
            @click="switchMode(isRegister ? 'login' : 'register')"
          >
            {{ isRegister ? '返回登录' : '注册' }}
          </el-button>
        </p>

        <p class="account-note">
          每个账号只能访问自己的收藏、正文和保存证据。登录后会进入收藏，抓取任务在后台生成快照。
        </p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(0, 7fr) minmax(430px, 5fr);
  min-height: 100svh;
  background: var(--ground);
}

.intro-panel {
  position: relative;
  display: flex;
  min-height: 100svh;
  flex-direction: column;
  padding: clamp(40px, 5vw, 72px);
  overflow: hidden;
}

.product-identity {
  display: flex;
  align-items: center;
  gap: 12px;
}

h1 {
  margin: 0;
  color: var(--text);
  font-size: clamp(34px, 2.7vw, 40px);
  font-weight: 650;
  letter-spacing: -0.035em;
  line-height: 1.05;
}

.intro-copy {
  width: min(700px, 100%);
  margin: auto 0;
  padding: clamp(64px, 9vh, 104px) 0 clamp(48px, 7vh, 76px);
}

.manifesto {
  max-width: 13em;
  margin: 0;
  color: var(--text);
  font-size: clamp(30px, 2.45vw, 38px);
  font-weight: 500;
  letter-spacing: -0.035em;
  line-height: 1.22;
}

.supporting-copy {
  max-width: 36em;
  margin: 28px 0 0;
  color: var(--muted);
  font-size: 16px;
  line-height: 1.75;
}

.archive-preview {
  width: min(720px, 100%);
  margin: 0;
}

.archive-card {
  position: relative;
  padding: 22px 24px 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
}

.archive-card::before,
.archive-card::after {
  position: absolute;
  right: 14px;
  bottom: -7px;
  width: calc(100% - 28px);
  height: 14px;
  border: 1px solid var(--border);
  border-radius: 0 0 var(--radius-panel) var(--radius-panel);
  background: var(--active);
  content: "";
}

.archive-card::before {
  right: 7px;
  bottom: -4px;
  width: calc(100% - 14px);
  background: var(--raised);
}

.archive-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 500;
}

.readable-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--accent);
}

.readable-status > span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.archive-body {
  padding: 22px 0 26px;
}

.archive-lead {
  margin: 0;
  color: var(--text);
  font-size: 22px;
  font-weight: 550;
  letter-spacing: -0.025em;
  line-height: 1.35;
}

.archive-copy {
  max-width: 42em;
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.7;
}

.evidence-band {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--rule);
}

.evidence-item {
  display: grid;
  gap: 4px;
  min-height: 76px;
  align-content: center;
  padding: 0 20px;
}

.evidence-item:first-child {
  padding-left: 0;
}

.evidence-item + .evidence-item {
  border-left: 1px solid var(--rule);
}

.evidence-item strong {
  color: var(--text);
  font-size: 14px;
  font-weight: 600;
}

.evidence-item span {
  color: var(--muted);
  font-size: 12px;
}

.auth-panel {
  display: grid;
  min-height: 100svh;
  place-items: center;
  padding: 126px clamp(36px, 5vw, 80px) 0;
  background: var(--surface);
}

.auth-content {
  width: min(100%, 404px);
}

.auth-heading {
  margin-bottom: 34px;
}

.auth-heading h2 {
  margin: 0;
  color: var(--text);
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.25;
}

.auth-heading p {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.65;
}

.auth-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.auth-form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 8px;
  padding: 0;
  color: var(--text);
  font-size: 14px;
  font-weight: 500;
  line-height: 1.4;
}

.auth-form :deep(.el-input__wrapper) {
  min-height: 46px;
  padding: 1px 14px;
  border: 1px solid var(--control);
  border-radius: var(--radius-control);
  background: var(--surface);
  box-shadow: none; /* taste-skill-ignore shadow */
  transition:
    border-color 160ms ease-out,
    background-color 160ms ease-out;
}

.auth-form :deep(.el-input__wrapper:hover) {
  border-color: var(--control-hover);
}

.auth-form :deep(.el-input__wrapper.is-focus) {
  border-color: var(--accent);
  outline: 3px solid var(--accent-soft);
  outline-offset: 1px;
}

.auth-form :deep(.el-input__inner) {
  color: var(--text);
  font-size: 15px;
}

.auth-form :deep(.el-input__inner::placeholder) {
  color: var(--muted);
}

.auth-form :deep(.el-form-item__error) {
  padding-top: 5px;
  color: var(--negative);
  font-size: 12px;
  line-height: 1.4;
}

.form-error {
  margin: -2px 0 16px;
  border-left: 3px solid var(--negative);
  padding: 9px 12px;
  color: var(--negative);
  background: var(--negative-soft);
  font-size: 13px;
  line-height: 1.5;
}

.submit-button {
  width: 100%;
  min-height: 46px;
  margin-top: 4px;
  border: 1px solid var(--text);
  border-radius: var(--radius-pill);
  background: var(--text);
  font-size: 15px;
  font-weight: 500;
}

.submit-button:not(.is-disabled):hover,
.submit-button:not(.is-disabled):focus {
  border-color: var(--text);
  background: var(--text);
}

.submit-button.is-disabled {
  opacity: 0.72;
}

.mode-switch {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  margin: 24px 0 0;
  color: var(--muted);
  font-size: 14px;
}

.mode-switch :deep(.el-button) {
  min-height: 32px;
  color: var(--text);
  font-size: 14px;
  font-weight: 550;
}

.account-note {
  margin: 36px 0 0;
  border-top: 1px solid var(--rule);
  padding-top: 18px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.65;
}

@media (max-width: 1100px) {
  .login-page {
    grid-template-columns: minmax(0, 1fr) minmax(410px, 0.78fr);
  }

  .intro-panel {
    padding: 40px;
  }

  .manifesto {
    font-size: 32px;
  }
}

@media (max-width: 860px) {
  .login-page {
    display: block;
  }

  .intro-panel {
    min-height: auto;
    padding: 32px 40px 40px;
    overflow: visible;
  }

  .intro-copy {
    margin: 0;
    padding: 56px 0 40px;
  }

  .manifesto {
    max-width: 16em;
  }

  .auth-panel {
    min-height: auto;
    padding: 48px 40px 64px;
    border-top: 1px solid var(--border);
    border-left: 0;
  }
}

@media (max-width: 600px) {
  .intro-panel {
    padding: 24px 20px 28px;
  }

  .product-identity {
    gap: 10px;
  }

  h1 {
    font-size: 28px;
  }

  .intro-copy {
    padding: 40px 0 30px;
  }

  .manifesto {
    font-size: 28px;
  }

  .supporting-copy {
    margin-top: 18px;
    font-size: 15px;
    line-height: 1.65;
  }

  .archive-card {
    padding: 16px 16px 0;
  }

  .archive-header,
  .archive-body {
    display: none;
  }

  .evidence-band {
    border-top: 0;
  }

  .evidence-item {
    min-height: 62px;
    padding: 0 10px;
  }

  .evidence-item:first-child {
    padding-left: 0;
  }

  .evidence-item strong {
    font-size: 13px;
  }

  .evidence-item span {
    font-size: 11px;
  }

  .auth-panel {
    padding: 36px 20px 48px;
  }

  .auth-heading {
    margin-bottom: 28px;
  }

  .mode-switch {
    margin-top: 20px;
  }

  .mode-switch :deep(.el-button) {
    min-height: 44px;
  }

  .account-note {
    margin-top: 24px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .auth-form :deep(.el-input__wrapper) {
    transition: none;
  }
}
</style>

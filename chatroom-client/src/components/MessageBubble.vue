<template>
  <div class="message-wrapper" :class="{ mine: isMine }">
    <div class="message-avatar" v-if="!isMine">
      <el-avatar :src="message.senderAvatar || undefined">{{ (message.senderName || '?')[0] }}</el-avatar>
    </div>
    <div class="message-body">
      <div class="message-sender" v-if="!isMine">{{ message.senderName }}</div>
      <!-- Reply reference -->
      <div v-if="message.replyToId" class="reply-ref">
        <span class="reply-name">{{ message.replyToSenderName || '...' }}</span>
        <span class="reply-content">{{ truncate(message.replyToContent, 50) }}</span>
      </div>
      <div class="message-bubble">
        <!-- Image preview -->
        <div v-if="message.contentType === 1" class="message-image">
          <el-image :src="extractImageUrl(message.content)" :preview-src-list="[extractImageUrl(message.content)]"
            fit="cover" style="max-width:240px;max-height:320px;border-radius:12px;cursor:pointer" />
        </div>
        <!-- File download -->
        <div v-else-if="message.contentType === 2" class="message-file">
          <a :href="extractFileUrl(message.content)" target="_blank" class="file-link">
            <el-icon :size="20"><Document /></el-icon>
            <span class="file-name">{{ extractFileName(message.content) }}</span>
            <el-icon :size="14"><Download /></el-icon>
          </a>
        </div>
        <div v-if="message.contentType !== 1" class="message-text" v-html="highlightMentions(message.content)"></div>
        <div class="message-meta">
          <span class="message-time">{{ formatTime(message.createdAt) }}</span>
          <span v-if="isMine" class="message-status">
            <el-icon class="status-icon" color="rgba(255,255,255,0.7)"><Checked /></el-icon>
          </span>
        </div>
      </div>
      <!-- Actions on hover -->
      <div class="message-actions">
        <button class="action-btn reply" @click="$emit('reply', message)">回复</button>
        <button v-if="isMine && isRecallable(message)" class="action-btn recall"
          @click="$emit('recall', message)">撤回</button>
        <button v-if="isMine" class="action-btn del"
          @click="$emit('deleteMsg', message)">删除</button>
      </div>
    </div>
    <div class="message-avatar" v-if="isMine">
      <el-avatar :src="message.senderAvatar || undefined">{{ (message.senderName || '?')[0] }}</el-avatar>
    </div>
  </div>
</template>

<script setup>
import { Checked, Document, Download } from '@element-plus/icons-vue'

const props = defineProps({
  message: { type: Object, required: true },
  isMine: { type: Boolean, default: false }
})

defineEmits(['reply', 'recall', 'deleteMsg'])

function extractImageUrl(content) {
  const m = content?.match(/\/api\/files\/[^\s]+/)
  return m ? m[0] : ''
}
function extractFileUrl(content) {
  const m = content?.match(/\/api\/files\/[^\s]+/)
  return m ? m[0] : ''
}
function extractFileName(content) {
  const m = content?.match(/\[文件\]\s+(.+?)\s+\/api\/files\//)
  return m ? m[1] : '下载文件'
}

function formatTime(timeStr) {
  if (!timeStr) return ''
  const d = new Date(timeStr)
  const h = String(d.getHours()).padStart(2, '0')
  const m = String(d.getMinutes()).padStart(2, '0')
  return `${h}:${m}`
}

function truncate(text, len) {
  if (!text) return ''
  return text.length > len ? text.substring(0, len) + '...' : text
}

function highlightMentions(text) {
  if (!text) return ''
  return text
    .replace(/@全体成员/g, '<span class="mention-highlight mention-all">@全体成员</span>')
    .replace(/@(\S+)/g, '<span class="mention-highlight">@$1</span>')
}

function isRecallable(msg) {
  if (!msg.createdAt) return false
  return (Date.now() - new Date(msg.createdAt).getTime()) < 2 * 60 * 1000
}
</script>

<style scoped>
.message-wrapper {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  align-items: flex-start;
  animation: message-appear 0.3s ease-out;
}

@keyframes message-appear {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message-wrapper.mine {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  flex-shrink: 0;
  font-size: 14px;
  font-weight: 600;
}

.message-body {
  max-width: 65%;
}

.mine .message-body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.message-sender {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 6px;
  margin-left: 2px;
}

.reply-ref {
  font-size: 13px;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.08), rgba(139, 92, 246, 0.08));
  padding: 8px 12px;
  border-radius: 12px;
  margin-bottom: 8px;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  border-left: 3px solid var(--primary-color);
}

.reply-name {
  color: var(--primary-color);
  font-weight: 500;
  margin-right: 8px;
}

.reply-content {
  color: var(--text-secondary);
}

.message-bubble {
  padding: 14px 18px;
  border-radius: 20px;
  background: var(--bg-primary);
  box-shadow: var(--shadow-sm);
  position: relative;
  transition: all 0.2s ease;
}

.message-bubble:hover {
  box-shadow: var(--shadow-md);
}

.mine .message-bubble {
  background: linear-gradient(135deg, var(--primary-color), var(--secondary-color));
}

.message-image {
  margin-bottom: 4px;
}
.message-file {
  margin-bottom: 6px;
}
.file-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: rgba(0,0,0,0.05);
  border-radius: 10px;
  color: var(--primary-color);
  text-decoration: none;
  font-size: 13px;
  transition: background 0.2s;
}
.file-link:hover { background: rgba(0,0,0,0.1); }
.file-name { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.message-text {
  font-size: 15px;
  line-height: 1.6;
  word-break: break-word;
  color: var(--text-primary);
}

.mine .message-text {
  color: white;
}

.message-meta {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 6px;
}

.message-time {
  font-size: 12px;
  color: var(--text-muted);
}

.mine .message-time {
  color: rgba(255, 255, 255, 0.7);
}

.message-status {
  display: flex;
  align-items: center;
  gap: 2px;
}

.status-icon {
  width: 16px;
  height: 16px;
}

.message-actions {
  display: none;
  gap: 6px;
  margin-top: 8px;
  padding: 6px;
  border-radius: 10px;
  background: var(--bg-primary);
  box-shadow: var(--shadow-md);
}

.mine .message-actions {
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(10px);
}

.message-wrapper:hover .message-actions {
  display: flex;
}

.action-btn {
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  border: none;
  background: transparent;
}

.action-btn.reply {
  color: var(--primary-color);
}

.action-btn.reply:hover {
  background: rgba(99, 102, 241, 0.1);
}

.action-btn.recall {
  color: var(--error-color);
}

.action-btn.recall:hover {
  background: rgba(239, 68, 68, 0.1);
}

.action-btn.del {
  color: var(--error-color);
}

.action-btn.del:hover {
  background: rgba(239, 68, 68, 0.15);
}

:deep(.mention-highlight) {
  color: var(--primary-color); font-weight: 600;
  background: rgba(99,102,241,0.1); padding: 1px 4px; border-radius: 4px;
}
:deep(.mention-all) {
  color: #fff; background: var(--error-color);
}

.mine .action-btn.reply {
  color: rgba(255, 255, 255, 0.9);
}

.mine .action-btn.reply:hover {
  background: rgba(255, 255, 255, 0.2);
}

.mine .action-btn.recall {
  color: #fca5a5;
}

.mine .action-btn.recall:hover {
  background: rgba(252, 165, 165, 0.2);
}
</style>

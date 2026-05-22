<template>
  <el-dialog v-model="visible" title="创建群聊" width="500px" @open="onOpen">
    <el-form :model="form" label-width="80px">
      <el-form-item label="群名称" required>
        <el-input v-model="form.name" placeholder="请输入群名称" maxlength="100" />
      </el-form-item>
      <el-form-item label="选择成员">
        <el-select v-model="form.memberIds" multiple filterable placeholder="搜索并选择好友（建议加入机器人让群更活跃）"
          style="width:100%">
          <el-option v-for="f in contactStore.friendList" :key="f.friendId"
            :label="(f.nickname || f.username) + (f.isBot ? ' [Bot]' : '')" :value="f.friendId">
            <span>{{ f.nickname || f.username }}</span>
            <el-tag v-if="f.isBot" size="small" type="success" style="margin-left:8px">Bot</el-tag>
          </el-option>
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="handleCreate" :loading="creating" :disabled="!form.name.trim()">
        创建
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, computed } from 'vue'
import { useContactStore } from '../store/contact'
import { createGroup } from '../api/group'
import { ElMessage } from 'element-plus'

const props = defineProps({
  visible: Boolean
})
const emit = defineEmits(['update:visible', 'done'])

const visible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const contactStore = useContactStore()
const creating = ref(false)

const form = reactive({
  name: '',
  memberIds: []
})

function onOpen() {
  form.name = ''
  form.memberIds = []
}

async function handleCreate() {
  if (!form.name.trim()) return
  creating.value = true
  try {
    await createGroup({ name: form.name.trim(), memberIds: form.memberIds })
    ElMessage.success('群聊创建成功')
    visible.value = false
    emit('done')
  } catch (e) {
    // Handled by interceptor
  } finally {
    creating.value = false
  }
}
</script>

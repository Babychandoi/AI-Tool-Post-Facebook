const status = document.getElementById('status');
const labels = { IDLE:'Đã kết nối. Sẵn sàng tạo ảnh tự động.', GENERATING:'Đang tạo và lấy ảnh từ ChatGPT…', LOGIN_REQUIRED:'Hãy đăng nhập ChatGPT trong Cốc Cốc rồi bấm Kiểm tra lại.', DISCONNECTED:'Chưa kết nối với ứng dụng.', QUOTA:'ChatGPT hết lượt ảnh. Chờ đặt lại hạn mức, hoặc đổi tài khoản rồi bấm Kiểm tra lại.', ERROR:'Tạo ảnh chưa hoàn tất. Kiểm tra tab ChatGPT được giữ lại.' };
async function refresh() {
  const { connectorState, lastFailure } = await chrome.storage.local.get(['connectorState', 'lastFailure']);
  status.textContent = (labels[connectorState?.phase] || 'Nhập mã kết nối từ Đăng tự động → Ảnh cho bài viết.') + (connectorState?.error ? ' (' + connectorState.error + ')' : '');
  document.getElementById('error-details').hidden = !lastFailure;
  document.getElementById('error-info').textContent = lastFailure ? [lastFailure.code, lastFailure.stage, ...(lastFailure.button_labels || [])].join('\n') : '';
}
document.getElementById('pair').onclick = async () => {
  const code = document.getElementById('code').value.trim(); if (!/^\d{6}$/.test(code)) { status.textContent = 'Nhập mã gồm 6 chữ số.'; return; }
  const result = await chrome.runtime.sendMessage({ type:'PAIR', code });
  status.textContent = result.ok ? 'Đã ghép nối. Đang kiểm tra đăng nhập ChatGPT…' : result.code === 'PAIRING_INVALID' ? 'Mã sai hoặc hết hạn. Tạo mã mới trong ứng dụng.' : 'Chưa kết nối được. Kiểm tra ứng dụng đang chạy và tạo mã mới.';
  if (result.ok) document.getElementById('code').value = '';
};
document.getElementById('check').onclick = () => chrome.runtime.sendMessage({ type:'CHECK' });
document.getElementById('disconnect').onclick = () => chrome.runtime.sendMessage({ type:'DISCONNECT' });
chrome.storage.onChanged.addListener(refresh); refresh();

/// 同行生命周期操作及确认时展示的实际影响。
enum GatheringLifecycleAction {
  leave('退出同行', '退出后将失去同行访问权限，你关联的瞬间会从同行移出，原始记录和图片保留。', '已退出同行'),
  end('结束同行', '结束后停止新成员加入与新增瞬间关联。现有成员仍可浏览、退出和移出自己的瞬间。', '同行已结束'),
  dissolve('解散同行', '解散后所有成员失去同行访问权限，全部瞬间解除关联，原始记录和图片保留。', '同行已解散');

  const GatheringLifecycleAction(
    this.label,
    this.confirmation,
    this.successMessage,
  );
  final String label;
  final String confirmation;
  final String successMessage;
}

/// 对应后端心情枚举，草稿与界面沿用中文名称。
const momentMoodCodes = {
  '开心': 'HAPPY',
  '平静': 'CALM',
  '松弛': 'RELAXED',
  '心动': 'SMITTEN',
  '惊喜': 'SURPRISED',
  '自在': 'CAREFREE',
  '低落': 'LOW',
  '焦虑': 'ANXIOUS',
};

String? momentMoodLabel(String? mood) {
  for (final entry in momentMoodCodes.entries) {
    if (entry.value == mood) return entry.key;
  }
  return mood;
}

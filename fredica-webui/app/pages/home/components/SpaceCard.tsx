
import React from 'react';
import { Box } from 'lucide-react';

interface SpaceCardProps {
  title: string;
  count: number;
  isEmpty?: boolean;
}

const SpaceCard: React.FC<SpaceCardProps> = ({ title, count, isEmpty = false }) => {
  if (isEmpty) {
    return (
      <button className="w-full h-40 border-2 border-dashed border-gray-200 rounded-2xl flex flex-col items-center justify-center gap-3 text-gray-400 hover:border-gray-300 hover:bg-gray-50/50 transition-all">
        <div className="w-10 h-10 bg-gray-100 rounded-full flex items-center justify-center">
          <span className="text-2xl font-light">+</span>
        </div>
        <span className="text-sm font-medium">创建空间</span>
      </button>
    );
  }

  return (
    <div className="w-full bg-white border border-gray-100 rounded-2xl overflow-hidden shadow-sm hover:shadow-md transition-shadow cursor-pointer group">
      <div className="h-10 bg-gray-50/80 border-b border-gray-100 px-4 flex items-center">
        {/* Placeholder for header accent if any */}
      </div>
      <div className="p-5 flex flex-col gap-1">
        <div className="text-gray-900 group-hover:text-emerald-600 transition-colors">
          <Box className="w-5 h-5 mb-2" />
        </div>
        <h3 className="text-base font-semibold text-gray-900">{title}</h3>
        <p className="text-sm text-gray-400">{count} 内容</p>
      </div>
    </div>
  );
};

export default SpaceCard;


import React from 'react';

interface ActionCardProps {
  icon: React.ReactNode;
  title: string;
  description: string;
}

const ActionCard: React.FC<ActionCardProps> = ({ icon, title, description }) => {
  return (
    <button className="flex items-center gap-4 p-5 bg-white border border-gray-100 rounded-3xl text-left hover:shadow-lg hover:border-gray-200 transition-all group">
      <div className="w-12 h-12 flex items-center justify-center bg-gray-50 rounded-2xl text-gray-500 group-hover:bg-gray-100 transition-colors">
        {icon}
      </div>
      <div>
        <h4 className="text-base font-semibold text-gray-900">{title}</h4>
        <p className="text-xs text-gray-400 leading-tight mt-0.5">{description}</p>
      </div>
    </button>
  );
};

export default ActionCard;
